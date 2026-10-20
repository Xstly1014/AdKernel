package com.adkernel.feature.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * 空值缓存（防穿透第二道防线）：兜布隆假阳性的伪造 ID + 合法无画像用户。
 *
 * <p>NULL 值短 TTL 30-60s（实证口径），不占长期内存。布隆拦截不了的（假阳性 0.01%）与
 * 合法无画像长尾（10%）在这里被吸收：首次回源后窗口内不再回源（DoD k08）。</p>
 */
public final class NullValueCache {

    private final ConcurrentHashMap<String, Long> expireAt = new ConcurrentHashMap<>();
    private final long ttlBaseMs;
    private final long ttlJitterMs;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public NullValueCache(long ttlBaseMs, long ttlJitterMs) {
        this.ttlBaseMs = ttlBaseMs;
        this.ttlJitterMs = ttlJitterMs;
    }

    public boolean contains(String userId) {
        Long at = expireAt.get(userId);
        if (at == null) {
            misses.increment();
            return false;
        }
        if (System.currentTimeMillis() >= at) {
            expireAt.remove(userId, at); // 惰性过期
            misses.increment();
            return false;
        }
        hits.increment();
        return true;
    }

    public void put(String userId) {
        long ttl = ttlBaseMs + ThreadLocalRandom.current().nextLong(ttlJitterMs + 1);
        expireAt.put(userId, System.currentTimeMillis() + ttl);
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }
}
