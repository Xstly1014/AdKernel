package com.adkernel.feature.bloom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 手写布隆过滤器（防穿透第一道防线）。
 *
 * <p>纪律：布隆说"不存在"一定不存在（前置拦截的性质来源），说"存在"可能不存在（假阳性）——
 * 所以它只能当第一道防线，永远不能当唯一防线（空值缓存兜假阳性）。
 * 50 万固定 key 集合是本场景适合布隆的前提（无需删除、无需热重构），前提条件已写进 ADR。</p>
 *
 * <p>参数推导（fpp=0.01%）：m = ceil(n·ln(1/fpp)/ln²2) ≈ 9.58M bits（~1.2MB 位图），
 * k = round(m/n·ln2) = 13 个哈希。位索引用 Kirsch-Mitzenmacher 双哈希：idx_i = (h1 + i·h2) % m，
 * 一次 sha256 产出 h1/h2——无三次哈希开销。</p>
 */
public final class BloomFilter {

    private final long[] words;
    private final int k;
    private final long m;

    public BloomFilter(long expectedEntries, double fpp) {
        if (expectedEntries <= 0 || fpp <= 0 || fpp >= 1) {
            throw new IllegalArgumentException("invalid bloom params: n=" + expectedEntries + " fpp=" + fpp);
        }
        this.m = (long) Math.ceil(-expectedEntries * Math.log(fpp) / (Math.log(2) * Math.log(2)));
        this.k = Math.max(1, (int) Math.round((double) m / expectedEntries * Math.log(2)));
        this.words = new long[(int) ((m + 63) >>> 6)];
    }

    public void add(String key) {
        long[] h = hash2(key);
        long h1 = h[0], h2 = h[1] == 0 ? 1 : h[1]; // h2 退化保护
        for (int i = 0; i < k; i++) {
            long bit = Math.floorMod(h1 + i * h2, m); // floorMod：哈希为负时索引仍非负
            int word = (int) (bit >>> 6);
            words[word] |= 1L << (bit & 63);
        }
    }

    /** true = 可能存在（假阳性率 fpp）；false = 一定不存在（拦截依据） */
    public boolean mightContain(String key) {
        long[] h = hash2(key);
        long h1 = h[0], h2 = h[1] == 0 ? 1 : h[1];
        for (int i = 0; i < k; i++) {
            long bit = Math.floorMod(h1 + i * h2, m);
            if ((words[(int) (bit >>> 6)] & (1L << (bit & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    public long bits() {
        return m;
    }

    public int hashes() {
        return k;
    }

    private static long[] hash2(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(key.getBytes(StandardCharsets.UTF_8));
            long h1 = 0, h2 = 0;
            for (int i = 0; i < 8; i++) {
                h1 = (h1 << 8) | (d[i] & 0xFF);
                h2 = (h2 << 8) | (d[8 + i] & 0xFF);
            }
            return new long[]{h1, h2};
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }
}
