package com.adkernel.simulator.config;

import java.util.List;
import java.util.Map;

/** 流量曲线配置（config/traffic.yaml）：每个 profile = 轮数 + 预热 + QPS 分相曲线 */
public record TrafficConfig(Map<String, Profile> profiles) {

    /**
     * rounds：重复轮数（M5 纪律：预热丢弃 + 3 轮取中位，基线口径从 M0 统一）；
     * warmupSec：预热秒数（统计与基线剔除，判罚照常）；
     * phases：QPS 曲线（分段常数，支持平峰/晚高峰/洪峰）。
     */
    public record Profile(int rounds, int warmupSec, List<Phase> phases) {}

    public record Phase(int qps, int sec) {}
}
