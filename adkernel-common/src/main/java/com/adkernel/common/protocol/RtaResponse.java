package com.adkernel.common.protocol;

/**
 * RTA 决策响应（AdKernel → 媒体）。
 * 宪法第二条：expId 闭环——广告主在响应里透传实验分组，媒体回传事件时原样带回（M4 归因分组依据）。
 */
public record RtaResponse(
        int code,        // 0 正常；错误码见协议 01 §5
        boolean target,  // 是否参竞
        double bidRatio, // 出价系数（华为 priceWeight 对齐字段），范围 [0, 10]
        String expId,    // 实验组透传
        String reason    // 决策原因码：echo / blacklist / invalid_signature ...
) {

    public static RtaResponse ok(boolean target, double bidRatio, String expId, String reason) {
        return new RtaResponse(0, target, bidRatio, expId, reason);
    }

    public static RtaResponse error(int code, String reason) {
        return new RtaResponse(code, false, 0, null, reason);
    }

    /** 错误码常量（协议 01 §5） */
    public static final int CODE_OK = 0;
    public static final int CODE_BAD_FIELD = 40001;
    public static final int CODE_BAD_SIGN = 40101;
    public static final int CODE_STALE_TIME = 40102;
}
