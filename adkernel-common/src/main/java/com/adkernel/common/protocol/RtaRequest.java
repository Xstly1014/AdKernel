package com.adkernel.common.protocol;

/**
 * RTA 决策请求（媒体 → AdKernel）。
 * 协议定义见 docs/protocol/01-rta-decision.md。
 *
 * <p>宪法第一条：requestId 全链路贯穿（响应/曝光/点击/转化/账单五类记录共用），
 * 它是归因（M4）、幂等（M3）、对账（M3）三条链路的共同钥匙。</p>
 */
public record RtaRequest(
        String requestId,   // UUID，全链路贯穿 ID
        long requestTime,   // 毫秒时间戳，参与签名 + 新鲜度校验
        String campaignId,  // 广告计划（M0 单计划 cmp-001，M2 起多计划路由）
        String userId,      // 脱敏用户哈希 ID（对齐华为 oaid/ifa 口径）
        String adSlotId,    // 广告位（slot-feed-1001 / slot-splash-2001）
        String scene,       // 场景（feed / splash），场景化策略输入
        String sign         // HmacSha256(requestId + requestTime + userId, secretKey)
) {
}
