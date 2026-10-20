package com.adkernel.feature.core;

import com.adkernel.common.user.UserProfile;
import com.adkernel.common.util.LatencyRecorder;
import com.adkernel.feature.bloom.BloomFilter;
import com.adkernel.feature.cache.L2Cache;
import com.adkernel.feature.cache.NullValueCache;
import com.adkernel.feature.source.ProfileSource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * 特征读路径编排：布隆前置 → 空值缓存 → L2 → 源（逐级回填）。
 *
 * <p>layer 观测口径（协议 04）：bloom / nullcache / l2 / source——
 * k07（布隆拦截率）/ k08（空值兜误判）的断言数据源；per-layer 延迟是三级延迟账本的直接数字。</p>
 *
 * <p>bypassLayers（k10 实验要件）：跳过布隆/空值/L2 直查源——"无缓存直查源"对照基线的
 * 特征服务侧开关（RTA 侧另有 L1 bypass，两者同开才是纯 12ms 口径）。</p>
 */
@Service
public class FeatureService {

    /** 查询结果：exist + 命中层 + 画像（null = 无画像/不存在） */
    public record FeatureResult(boolean exist, String layer, UserProfile profile) {
    }

    private final ProfileSource source;
    private final L2Cache l2;
    private final NullValueCache nullCache;
    private final BloomFilter bloom;
    private final LongAdder bloomIntercepted = new LongAdder();
    private final LongAdder bloomPassed = new LongAdder();
    private final Map<String, LatencyRecorder> layerLatency = Map.of(
            "bloom", new LatencyRecorder(4096),
            "nullcache", new LatencyRecorder(4096),
            "l2", new LatencyRecorder(8192),
            "source", new LatencyRecorder(8192));
    private volatile boolean bypassLayers;

    public FeatureService(ProfileSource source, L2Cache l2, NullValueCache nullCache, BloomFilter bloom) {
        this.source = source;
        this.l2 = l2;
        this.nullCache = nullCache;
        this.bloom = bloom;
    }

    public FeatureResult get(String userId) {
        if (bypassLayers) {
            long t0 = System.nanoTime();
            Optional<UserProfile> loaded = source.load(userId).join();
            layerLatency.get("source").record((System.nanoTime() - t0) / 1_000_000.0);
            return loaded.map(p -> new FeatureResult(true, "source", p))
                    .orElseGet(() -> new FeatureResult(false, "source", null));
        }
        // 1. 布隆前置：说"不存在"一定不存在（拦截伪造 ID）
        if (!bloom.mightContain(userId)) {
            long t0 = System.nanoTime();
            bloomIntercepted.increment();
            layerLatency.get("bloom").record((System.nanoTime() - t0) / 1_000_000.0);
            return new FeatureResult(false, "bloom", null);
        }
        bloomPassed.increment();
        // 2. 空值缓存：兜布隆假阳性 + 合法无画像（窗口内不回源）
        long t1 = System.nanoTime();
        if (nullCache.contains(userId)) {
            layerLatency.get("nullcache").record((System.nanoTime() - t1) / 1_000_000.0);
            return new FeatureResult(false, "nullcache", null);
        }
        layerLatency.get("nullcache").record((System.nanoTime() - t1) / 1_000_000.0);
        // 3. L2（模拟 Redis）
        long t2 = System.nanoTime();
        UserProfile cached = l2.get(userId);
        layerLatency.get("l2").record((System.nanoTime() - t2) / 1_000_000.0);
        if (cached != null) {
            return new FeatureResult(true, "l2", cached);
        }
        // 4. 源（5ms 注入延迟）→ 回填 L2（写失败不影响返回）→ 返回
        long t3 = System.nanoTime();
        Optional<UserProfile> loaded = source.load(userId).join(); // 虚拟线程上阻塞 OK
        layerLatency.get("source").record((System.nanoTime() - t3) / 1_000_000.0);
        if (loaded.isEmpty()) {
            nullCache.put(userId); // 回填空值（30-60s 窗口）
            return new FeatureResult(false, "source", null);
        }
        l2.put(userId, loaded.get());
        return new FeatureResult(true, "source", loaded.get());
    }

    public void setBypassLayers(boolean b) {
        this.bypassLayers = b;
    }

    public boolean bypassLayers() {
        return bypassLayers;
    }

    public Map<String, Object> stats() {
        Map<String, Object> layers = new LinkedHashMap<>();
        layerLatency.forEach((layer, rec) -> layers.put(layer, Map.of(
                "p50Ms", rec.p(0.50), "p99Ms", rec.p(0.99), "samples", rec.count())));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("bloom", Map.of("intercepted", bloomIntercepted.sum(), "passed", bloomPassed.sum()));
        s.put("nullCache", Map.of("hits", nullCache.hits(), "misses", nullCache.misses()));
        s.put("l2", Map.of("hits", l2.hits(), "misses", l2.misses(), "size", l2.size()));
        s.put("source", Map.of(
                "queries", source.totalQueries(),
                "p50Ms", source.sourceP50Ms(),
                "p99Ms", source.sourceP99Ms(),
                "injectLatencyMs", source.injectLatencyMs()));
        s.put("layerLatency", layers);
        s.put("bypassLayers", bypassLayers);
        s.put("distribution", source.distributionSummary());
        s.put("skippedUsers", source.skippedCount());
        s.put("poolSize", source.size());
        return s;
    }
}
