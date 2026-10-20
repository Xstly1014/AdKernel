package com.adkernel.feature.source;

import com.adkernel.common.user.MemberLevel;
import com.adkernel.common.user.UserIds;
import com.adkernel.common.user.UserProfile;
import com.adkernel.common.util.LatencyRecorder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 画像源：50 万用户画像（内存 Map + 延迟注入）——"广告主一方数据"的角色模拟。
 *
 * <p>
 * 确定性生成规则（契约 docs/feature-contract.md §3，与 M0 UserPool 同源）：
 * </p>
 * <ul>
 * <li>userId = "u-" + hex(sha256("adkernel|user|" + i)).substring(0,12)——前 10
 * 万与 M0 流量池逐 ID 重合；</li>
 * <li>黑名单：i % 100 == 0（与 M0 裁判真值一致）；</li>
 * <li>无画像长尾 10%：i % 10 == 7 且非黑名单（黑名单用户必有画像，否则默认画像会误放行）；</li>
 * <li>会员 10%（VIP 3%）、7d 内转化 5%、新用户（&lt;3d）5%——全部由 i 的确定性哈希单向推导，可复现。</li>
 * </ul>
 *
 * <p>
 * 延迟注入：{@code Thread.sleep} 承载在请求虚拟线程上（不会被 JIT 消除），
 * 默认 5ms 模拟 DB 网络往返 + 查询——回源成本的度量基准（L1 命中 1μs vs 源 5ms = 5000 倍差距）。
 * </p>
 */
public final class ProfileSource {

    private static final long DAY_MS = 24L * 3600 * 1000;

    private final Map<String, UserProfile> profiles;
    private final java.util.Set<String> allUserIds; // 全量 50 万 ID（含无画像长尾——布隆 membership 真值）
    private final int skipped;
    private final long nowBase; // 时间基准：lastConvert/firstSeen 相对它偏移（分布语义跨启动可复现）
    private volatile long injectLatencyMs;
    private final LongAdder queries = new LongAdder();
    private final LatencyRecorder latency = new LatencyRecorder(8192);
    private final String distributionSummary;

    public ProfileSource(int poolSize, long injectLatencyMs) {
        this.nowBase = System.currentTimeMillis();
        this.injectLatencyMs = injectLatencyMs;
        this.profiles = new HashMap<>(poolSize * 2);
        this.allUserIds = new java.util.HashSet<>(poolSize * 2);
        int skip = 0;
        int vip = 0, member = 0, black = 0, converted = 0, fresh = 0;
        for (int i = 0; i < poolSize; i++) {
            String id = UserIds.userId(i);
            allUserIds.add(id);
            boolean isBlack = i % 100 == 0;
            if (isBlack) {
                black++;
            }
            if (i % 10 == 7 && !isBlack) {
                skip++; // 合法无画像长尾（10%）——仍在布隆内（合法用户），由空值缓存兜
                continue;
            }
            long[] h = hash4("adkernel|profile|" + i);
            // 符号归一化：sha256 拆出的 long 有一半概率为负，直接 % 100 会得负值污染百分比判断
            long h0 = nonNeg(h[0]), h1v = nonNeg(h[1]), h2v = nonNeg(h[2]), h3v = nonNeg(h[3]);
            MemberLevel level = h0 % 100 < 3 ? MemberLevel.VIP
                    : h0 % 100 < 10 ? MemberLevel.MEMBER : MemberLevel.NORMAL;
            if (level == MemberLevel.VIP)
                vip++;
            if (level == MemberLevel.MEMBER)
                member++;
            long lastConvert = h1v % 100 < 5 ? nowBase - (h1v / 100) % (7 * DAY_MS) : 0L;
            if (lastConvert > 0)
                converted++;
            boolean freshUser = h2v % 100 < 5;
            long firstSeen = freshUser ? nowBase - (h2v / 100) % (3 * DAY_MS)
                    : nowBase - (h2v / 100) % (365 * DAY_MS);
            if (freshUser)
                fresh++;
            int impressions = (int) ((h3v / 100) % 51);
            UserProfile p = new UserProfile(
                    id, level, isBlack,
                    lastConvert, impressions, firstSeen);
            profiles.put(p.userId(), p);
        }
        this.skipped = skip;
        int generated = poolSize - skip;
        this.distributionSummary = String.format(
                "generated=%d skipped=%d(%.1f%%) vip=%.2f%% member=%.2f%% blacklist=%.2f%% converted7d=%.2f%% fresh=%.2f%%",
                generated, skip, 100.0 * skip / poolSize,
                100.0 * vip / generated, 100.0 * member / generated,
                100.0 * black / poolSize, 100.0 * converted / generated,
                100.0 * fresh / generated);
    }

    /**
     * 源查询执行器：虚拟线程（k09 二跑根因修复）。
     *
     * <p>
     * 原实现 {@code supplyAsync(task)}（无参）落在 ForkJoinPool.commonPool——平台线程
     * 数 = CPU-1，{@code Thread.sleep} 直接占死载体：50ms 注入期源吞吐上限 ≈ 11线程/50ms
     * ≈ 220 QPS，实测 layerLatency.source P50=15.1s（97858 个查询在 commonPool 排队）。
     * 特征服务积压 → RTA 装载 RPC 100ms 硬超时全失败 → L1/L2 永远填不满 → miss 风暴正反馈。
     * 改虚拟线程执行器：sleep 即 unmount，吞吐不再受平台线程数限制。
     * </p>
     */
    private final java.util.concurrent.ExecutorService sourceExecutor = java.util.concurrent.Executors
            .newVirtualThreadPerTaskExecutor();

    /** 查询（异步：注入延迟后完成——源层"慢"是缓存一切优化的度量前提）。 */
    public CompletableFuture<Optional<UserProfile>> load(String userId) {
        return CompletableFuture.supplyAsync(() -> {
            queries.increment();
            long t0 = System.nanoTime();
            try {
                if (injectLatencyMs > 0) {
                    Thread.sleep(injectLatencyMs); // 虚拟线程上阻塞，模拟 DB 往返
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latency.record((System.nanoTime() - t0) / 1_000_000.0);
            return Optional.ofNullable(profiles.get(userId));
        }, sourceExecutor);
    }

    public int size() {
        return profiles.size();
    }

    /** 全量用户 ID（含无画像长尾——布隆 membership 真值：伪造 ID 不在集合内） */
    public Iterable<String> allUserIds() {
        return allUserIds;
    }

    public int skippedCount() {
        return skipped;
    }

    public String distributionSummary() {
        return distributionSummary;
    }

    public long totalQueries() {
        return queries.sum();
    }

    public double sourceP50Ms() {
        return latency.p(0.50);
    }

    public double sourceP99Ms() {
        return latency.p(0.99);
    }

    public void setInjectLatencyMs(long ms) {
        this.injectLatencyMs = ms;
    }

    public long injectLatencyMs() {
        return injectLatencyMs;
    }

    private static long nonNeg(long v) {
        return v & Long.MAX_VALUE;
    }

    /** sha256 拆 4 个 long（确定性分布哈希，无随机状态） */
    private static long[] hash4(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return new long[] {
                    pickLong(d, 0), pickLong(d, 8), pickLong(d, 16), pickLong(d, 24) };
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    private static long pickLong(byte[] d, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (d[off + i] & 0xFF);
        }
        return v;
    }
}
