package com.adkernel.simulator.config;

/**
 * 转化漏斗配置（config/funnel.yaml）——M4 对照实验的"调节旋钮"：
 * 基线组与实验组走同一套漏斗概率，唯一变量是决策逻辑；概率硬编码则实验调不了，两组概率不同则是作弊。
 */
public record FunnelConfig(
        double winRate,  // 竞价胜出率（简化：不模拟真实竞价，M5 归因说明裁剪）
        double ctr,      // 点击率：信息流行业基准 1%-3%
        double cvr,      // 转化率：电商行业基准 1%-5%
        double cpcMin,   // CPC 单价下界（点击计费金额采样区间）
        double cpcMax    // CPC 单价上界
) {
}
