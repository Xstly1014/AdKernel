package com.adkernel.common.util;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 滚动样本延迟记录器（环形覆盖）：低开销记录 + 快照取分位。
 * 用于观测口径的分位数输出（命中率挡板之后的层流量小，样本容量有限即可）。
 */
public final class LatencyRecorder {

    private final double[] samples;
    private final AtomicInteger idx = new AtomicInteger();

    public LatencyRecorder(int cap) {
        this.samples = new double[cap];
    }

    public void record(double ms) {
        samples[Math.floorMod(idx.getAndIncrement(), samples.length)] = ms;
    }

    public double p(double q) {
        int n = count();
        if (n == 0) {
            return 0;
        }
        double[] copy = new double[n];
        System.arraycopy(samples, 0, copy, 0, n);
        Arrays.sort(copy);
        int i = (int) Math.min(n - 1, Math.round(q * (n - 1)));
        return copy[i];
    }

    public int count() {
        return Math.min(idx.get(), samples.length);
    }
}
