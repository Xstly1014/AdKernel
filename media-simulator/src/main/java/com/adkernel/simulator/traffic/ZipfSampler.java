package com.adkernel.simulator.traffic;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Zipf 幂律采样器：CDF + 二分查找（10 万用户 × 万级 QPS 下每采样 ~17 次比较，开销可忽略）。
 *
 * <p>为什么是幂律而不是均匀：M1 缓存命中率的全部意义建立在热用户存在的前提上——
 * 均匀分布下任何局部缓存约等于全量缓存，命中率优化的叙事直接作废。</p>
 *
 * <p>指数校准：s=1.0 纯 Zipf 的 Top20% 份额 ≈87%；取 s=0.86 校准到 ≈80%（DoD k06 口径）。</p>
 */
public final class ZipfSampler {

    private final double[] cdf;
    private final double expectedTop20Share;

    public ZipfSampler(int n, double s) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        double[] w = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            w[i] = 1.0 / Math.pow(i + 1, s);
            total += w[i];
        }
        this.cdf = new double[n];
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += w[i] / total;
            cdf[i] = acc;
        }
        cdf[n - 1] = 1.0;
        int k = (int) Math.ceil(n * 0.2);
        double top = 0;
        for (int i = 0; i < k; i++) {
            top += w[i];
        }
        this.expectedTop20Share = top / total;
    }

    /** 采样一个用户下标（rank 0 = 最热用户） */
    public int sample() {
        double u = ThreadLocalRandom.current().nextDouble();
        int lo = 0, hi = cdf.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** 分布的理论 Top20% 份额（报告对照用） */
    public double expectedTop20Share() {
        return expectedTop20Share;
    }
}
