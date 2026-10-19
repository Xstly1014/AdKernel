package com.adkernel.simulator.traffic;

import com.adkernel.simulator.config.TrafficConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * QPS 曲线：预热段 + 分段常数相位（平峰/晚高峰/洪峰 spike）。
 * 期望发射数用分段精确积分（∫qps(t)dt），调度器据此追帧——曲线切换无锯齿。
 */
public final class QpsCurve {

    private final int warmupSec;
    private final int[] phaseQps;
    private final int[] phaseLen;
    private final double[] phaseStart; // 相位起点（预热后的秒数）
    private final double totalSec;

    public QpsCurve(TrafficConfig.Profile profile) {
        this.warmupSec = profile.warmupSec();
        List<TrafficConfig.Phase> phases = profile.phases();
        if (phases == null || phases.isEmpty()) {
            throw new IllegalArgumentException("profile must have at least one phase");
        }
        this.phaseQps = new int[phases.size()];
        this.phaseLen = new int[phases.size()];
        this.phaseStart = new double[phases.size()];
        double acc = 0;
        for (int i = 0; i < phases.size(); i++) {
            TrafficConfig.Phase p = phases.get(i);
            if (p.qps() <= 0 || p.sec() <= 0) {
                throw new IllegalArgumentException("invalid phase: qps=" + p.qps() + ", sec=" + p.sec());
            }
            phaseQps[i] = p.qps();
            phaseLen[i] = p.sec();
            phaseStart[i] = acc;
            acc += p.sec();
        }
        this.totalSec = warmupSec + acc;
    }

    /** 时刻 t（秒，含预热）的目标 QPS */
    public double qpsAt(double t) {
        if (t < warmupSec) {
            return phaseQps[0];
        }
        double x = t - warmupSec;
        for (int i = 0; i < phaseQps.length; i++) {
            if (x < phaseStart[i] + phaseLen[i]) {
                return phaseQps[i];
            }
        }
        return phaseQps[phaseQps.length - 1];
    }

    /** 期望累计发射数 = ∫0..t qps(u)du（分段常数精确积分，预热段按首相位 QPS） */
    public double expectedCount(double t) {
        double count = Math.min(Math.max(t, 0), warmupSec) * phaseQps[0];
        if (t <= warmupSec) {
            return count;
        }
        double x = t - warmupSec;
        for (int i = 0; i < phaseQps.length; i++) {
            double overlap = Math.min(x, phaseStart[i] + phaseLen[i]) - phaseStart[i];
            if (overlap > 0) {
                count += overlap * phaseQps[i];
            }
        }
        return count;
    }

    public boolean isWarmup(double sec) {
        return sec < warmupSec;
    }

    /** 秒索引 → 相位名（分桶统计口径："warmup" / "p1-10000qps" / 洪峰相位即独立桶） */
    public String phaseNameAt(double sec) {
        if (sec < warmupSec) {
            return "warmup";
        }
        double x = sec - warmupSec;
        for (int i = 0; i < phaseQps.length; i++) {
            if (x < phaseStart[i] + phaseLen[i]) {
                return "p" + (i + 1) + "-" + phaseQps[i] + "qps";
            }
        }
        return "p" + phaseQps.length + "-" + phaseQps[phaseQps.length - 1] + "qps";
    }

    /** 相位名列表（报告排序用；洪峰 spike 相位自然单独成桶） */
    public List<String> phaseNames() {
        List<String> names = new ArrayList<>();
        if (warmupSec > 0) {
            names.add("warmup");
        }
        for (int i = 0; i < phaseQps.length; i++) {
            names.add("p" + (i + 1) + "-" + phaseQps[i] + "qps");
        }
        return names;
    }

    /** 相位时长（秒）——报告计算相位内平均 QPS */
    public double phaseDurationSec(String name) {
        if ("warmup".equals(name)) {
            return warmupSec;
        }
        for (int i = 0; i < phaseQps.length; i++) {
            if (("p" + (i + 1) + "-" + phaseQps[i] + "qps").equals(name)) {
                return phaseLen[i];
            }
        }
        return 0;
    }

    public double totalSec() {
        return totalSec;
    }

    public int warmupSec() {
        return warmupSec;
    }
}
