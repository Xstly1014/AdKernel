package com.adkernel.rta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** RTA 服务端配置（application.yml 中 rta.* 前缀，可被 CLI --rta.xxx=value 覆盖） */
@ConfigurationProperties(prefix = "rta")
public record RtaProperties(
        String secretKey, // 与媒体模拟器共享的签名密钥
        long signTtlMs, // 请求新鲜度窗口（防重放），默认 60s
        String expId, // echo 阶段恒定的实验组标识
        boolean faultBlacklistBypass, // 故障注入：跳过黑名单检查（k07 断言生效实证用）
        double decisionSampleRate, // 决策日志抽样率（黑名单拒绝 100% 记录）
        String dataDir, // 自记落盘目录（decisions / events JSONL）
        String featureBaseUrl, // 特征服务地址（M1：L1 miss 的回源通道）
        long featureTimeoutMs, // 特征 RPC 硬超时（装载完成窗口：宽于冷装载 6-8ms、窄于故障注入），默认 100ms
        long l1MaxSize, // L1 容量：50 万 × 热头部 20%（幂律反推）
        long l1RefreshSec, // refreshAfterWrite：异步刷新节奏（stale 兜底的一半）
        long l1ExpireMin // expireAfterWrite：旧值可读性上界（比 L2 的 5min 长）
) {
}
