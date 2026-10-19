package com.adkernel.simulator.http;

/** HTTP 调用结果（客户端观测口径：含序列化与网络往返） */
public record ApiResult(
        int status,     // HTTP 状态码（超时/IO 错误为 0）
        String body,    // 响应体
        boolean timedOut, // 客户端 60ms 超时（媒体按默认策略处理=不参竞）
        boolean ioError   // 连接失败等 IO 异常
) {
    public boolean httpOk() {
        return !timedOut && !ioError && status == 200;
    }
}
