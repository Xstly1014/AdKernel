package com.adkernel.common.user;

/**
 * 用户画像（特征模型，6 字段——契约见 docs/feature-contract.md，无一悬空字段）。
 *
 * <ul>
 * <li>memberLevel：M2 价值分层出价；</li>
 * <li>blacklisted：M1 决策拒绝 + M0 裁判断言的真值承载；</li>
 * <li>lastConvertTime：M2 "已购未满 7d 不参竞"，M4 转化回流更新目标；</li>
 * <li>impressionCount7d：M2 频控统计基础；</li>
 * <li>firstSeenTime：M2 新用户冷启动识别（注册 &lt; 3d 走保守策略）。</li>
 * </ul>
 */
public record UserProfile(
        String userId,
        MemberLevel memberLevel,
        boolean blacklisted,
        long lastConvertTime,   // epoch ms；0 = 从未转化
        int impressionCount7d,
        long firstSeenTime      // epoch ms
) {
}
