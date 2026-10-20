package com.adkernel.feature.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 特征服务配置（application.yml 中 feature.* 前缀，可被 CLI --feature.xxx=value 覆盖） */
@ConfigurationProperties(prefix = "feature")
public record FeatureProperties(
        int poolSize,          // 画像规模（默认 50 万）
        long sourceLatencyMs,  // 源层延迟注入（回源成本基准；k09 实验可调 50）
        long l2TtlBaseMs,      // L2 基础 TTL
        long l2TtlJitterMs,    // L2 TTL 随机偏移上限（防雪崩）
        long nullTtlBaseMs,    // 空值缓存基础 TTL
        long nullTtlJitterMs,  // 空值缓存 TTL 抖动上限
        double bloomFpp        // 布隆误判率
) {
}
