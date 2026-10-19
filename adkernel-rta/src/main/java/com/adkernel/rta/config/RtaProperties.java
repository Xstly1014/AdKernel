package com.adkernel.rta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** RTA 服务端配置（application.yml 中 rta.* 前缀，可被 CLI --rta.xxx=value 覆盖） */
@ConfigurationProperties(prefix = "rta")
public record RtaProperties(
        String secretKey,          // 与媒体模拟器共享的签名密钥
        long signTtlMs,            // 请求新鲜度窗口（防重放），默认 60s
        String expId,              // echo 阶段恒定的实验组标识
        boolean faultBlacklistBypass, // 故障注入：跳过黑名单检查（k07 断言生效实证用）
        double decisionSampleRate, // 决策日志抽样率（黑名单拒绝 100% 记录）
        String dataDir             // 自记落盘目录（decisions / events JSONL）
) {
}
