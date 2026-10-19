package com.adkernel.common.protocol;

/**
 * 模拟账单记录（T+1 日级 × 计划级，媒体侧生成）。
 * 协议定义见 docs/protocol/03-media-billing.md。
 *
 * <p>汇总口径：totalCost = SUM(去重后 CLICK.cost)（逐事件累加后一次性四舍五入到分）；
 * 按 requestId+eventType 去重；按 eventTime 自然日归属。</p>
 */
public record BillRecord(
        String date,        // 账单归属自然日（YYYY-MM-DD）
        String campaignId,
        double totalCost,   // 2 位小数
        long impressions,   // COUNT(去重后 EXPOSURE)
        long clicks         // COUNT(去重后 CLICK)
) {
}
