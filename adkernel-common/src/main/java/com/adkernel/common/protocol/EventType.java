package com.adkernel.common.protocol;

/** 回传事件类型：曝光 → 点击 → 转化三层漏斗（逐级递减不变式）。 */
public enum EventType {
    EXPOSURE,
    CLICK,     // CPC 计费发生的地方（对账口径基准）
    CONVERT    // 归因终点 + "已购即排除"的特征源（M4 特征回流）
}
