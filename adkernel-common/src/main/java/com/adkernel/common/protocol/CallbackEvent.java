package com.adkernel.common.protocol;

/**
 * 转化回传事件（媒体 → AdKernel，漏斗逐级上报）。
 * 协议定义见 docs/protocol/02-conversion-callback.md。
 *
 * <p>计费口径：CPC 计费挂在点击事件上（cost 仅 CLICK 携带，EXPOSURE/CONVERT 恒 0）——
 * 自记消耗 = SUM(CLICK.cost)，这是 M3 对账的基准口径。</p>
 */
public record CallbackEvent(
        String requestId,   // 与决策请求同一 ID（贯穿设计，闭包捕获，禁止重新生成）
        EventType eventType,
        long eventTime,     // 毫秒时间戳；账单按 eventTime 的自然日归属
        String campaignId,
        String expId,       // 决策响应里的分组被媒体原样带回（expId 闭环）
        double cost         // 仅 CLICK 事件携带 CPC 金额
) {
}
