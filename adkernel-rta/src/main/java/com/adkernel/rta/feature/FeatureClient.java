package com.adkernel.rta.feature;

import com.adkernel.common.user.UserProfile;
import com.adkernel.rta.config.RtaProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 特征服务 HTTP 客户端（协议 04）。
 *
 * <p>
 * 超时预算 3ms（特征查询总预算 5ms 的一半留给 L1 读取与降级路径）。
 * 返回 null = 无画像/不存在（布隆拦截或合法长尾）——调用方按保守策略处理。
 * </p>
 */
@Component
public class FeatureClient {

  /** 源查询结果：无画像语义（布隆拦截 / 合法长尾 / 空值缓存命中） */
  public record Fetched(UserProfile profile) {
    public boolean exists() {
      return profile != null;
    }
  }

  private final HttpClient http;
  private final String baseUrl;
  private final RtaProperties props;

  public FeatureClient(RtaProperties props) {
    // k09 二跑根因修复：HttpClient 默认 executor = ForkJoinPool.commonPool——sendAsync 的
    // 响应读取/future 完成回调全部落在 commonPool 平台线程上。装载风暴期（miss 率 50%+）
    // 回调与 Caffeine 异常处理争抢 CPU-1 个线程，RPC 完成延迟直接放大 100ms 硬超时失败率。
    // 换虚拟线程执行器：回调不占平台线程，超时回归纯网络/服务端口径。
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(500))
        .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
        .build();
    this.baseUrl = props.featureBaseUrl();
    this.props = props;
  }

  /** 异步查询（3ms 超时；超时/失败以异常完成——降级链接手） */
  public CompletableFuture<UserProfile> fetch(String userId) {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/internal/feature/" + userId))
        .timeout(Duration.ofMillis(props.featureTimeoutMs()))
        .GET()
        .build();
    return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(r -> {
          if (r.statusCode() != 200) {
            throw new IllegalStateException("feature service http " + r.statusCode());
          }
          return parse(r.body(), userId);
        });
  }

  /** 轻量 JSON 解析（响应体结构固定且小，避免引入完整反序列化开销） */
  private static UserProfile parse(String body, String userId) {
    if (!body.contains("\"exist\":true")) {
      return null; // 无画像/不存在（layer: bloom/nullcache/source）
    }
    String level = body.contains("\"VIP\"") ? "VIP"
        : body.contains("\"MEMBER\"") ? "MEMBER" : "NORMAL";
    return new UserProfile(
        userId,
        com.adkernel.common.user.MemberLevel.valueOf(level),
        body.contains("\"blacklisted\":true"),
        extractLong(body, "lastConvertTime"),
        (int) extractLong(body, "impressionCount7d"),
        extractLong(body, "firstSeenTime"));
  }

  private static long extractLong(String body, String field) {
    int i = body.indexOf("\"" + field + "\":");
    if (i < 0) {
      return 0;
    }
    int start = i + field.length() + 3;
    int end = start;
    while (end < body.length() && (Character.isDigit(body.charAt(end)))) {
      end++;
    }
    return end > start ? Long.parseLong(body.substring(start, end)) : 0;
  }
}
