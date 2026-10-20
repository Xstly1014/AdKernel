package com.adkernel.feature.cache;

import com.adkernel.common.user.UserProfile;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * L2 缓存：进程内模拟 Redis（ADR D2——延迟口径可控 + 零环境依赖 + 可精确注入 hang）。
 *
 * <p>对齐 Redis 语义：GET/SET + TTL 随机偏移（基础 5min + 0~60s，防雪崩——批量写入不集中过期）。
 * 过期采用惰性删除（get 时检查；条目被覆盖或闲置不清理也不增长：key 空间有限）。</p>
 *
 * <p>hang 注入（k09 实验要件）：{@link #hangFor(long)} 后所有 get 阻塞至窗口结束——
 * 模拟"Redis 整体 hang 30s"（真 Redis 反而难以精确注入）。</p>
 */
public final class L2Cache {

    private record Entry(UserProfile profile, long expireAt) {
    }

    private final ConcurrentHashMap<String, Entry> data = new ConcurrentHashMap<>();
    private final long ttlBaseMs;
    private final long ttlJitterMs;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private volatile long hangUntil;

    public L2Cache(long ttlBaseMs, long ttlJitterMs) {
        this.ttlBaseMs = ttlBaseMs;
        this.ttlJitterMs = ttlJitterMs;
    }

    /** TTL 随机偏移：基础 + [0, jitter]（k06 实验直接调用本方法验证偏移分布，不污染缓存） */
    public long ttlWithJitter() {
        return ttlBaseMs + ThreadLocalRandom.current().nextLong(ttlJitterMs + 1);
    }

    public UserProfile get(String userId) {
        long now = System.currentTimeMillis();
        if (now < hangUntil) {
            // hang 窗口内阻塞（虚拟线程上 park，不占载体线程）
            LockSupport.parkNanos((hangUntil - now) * 1_000_000L);
        }
        Entry e = data.get(userId);
        if (e == null) {
            misses.increment();
            return null;
        }
        if (System.currentTimeMillis() >= e.expireAt()) {
            data.remove(userId, e); // 惰性删除
            misses.increment();
            return null;
        }
        hits.increment();
        return e.profile();
    }

    /** 写入（生产纪律：写缓存失败不影响请求——调用侧无需 try-catch，本方法不抛） */
    public void put(String userId, UserProfile profile) {
        try {
            data.put(userId, new Entry(profile, System.currentTimeMillis() + ttlWithJitter()));
        } catch (Throwable ignore) {
            // 写缓存失败不影响返回（实证纪律 ①）
        }
    }

    /** hang 注入：ms 毫内所有 get 阻塞至窗口结束 */
    public void hangFor(long ms) {
        this.hangUntil = System.currentTimeMillis() + Math.max(0, ms);
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public long size() {
        return data.size();
    }
}
