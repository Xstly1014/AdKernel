package com.adkernel.simulator.http;

import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 媒体侧 HTTP 客户端：Java 21 HttpClient（HTTP/1.1 keep-alive 连接复用——Windows 端口耗尽的解法）。
 * 客户端超时 = 60ms 媒体红线：超时即放弃（媒体默认策略），延迟统计只含已应答请求。
 */
public final class RtaClient implements AutoCloseable {

    private final HttpClient http;
    private final Duration timeout;

    public RtaClient(int timeoutMs) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public ApiResult postJson(String url, Object payload) {
        String json = Json.write(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiResult(resp.statusCode(), resp.body(), false, false);
        } catch (java.net.http.HttpTimeoutException e) {
            return new ApiResult(0, null, true, false);
        } catch (Exception e) {
            return new ApiResult(0, null, false, true);
        }
    }

    /** 解析决策响应（解析失败返回 null，由裁判记协议错误） */
    public RtaResponse parseDecision(ApiResult r) {
        return r.body() == null ? null : Json.read(r.body(), RtaResponse.class);
    }

    @Override
    public void close() {
        http.close();
    }
}
