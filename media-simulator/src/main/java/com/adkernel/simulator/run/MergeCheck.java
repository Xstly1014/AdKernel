package com.adkernel.simulator.run;

import com.adkernel.common.auth.HmacSigner;
import com.adkernel.common.protocol.RtaRequest;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.user.UserIds;
import com.adkernel.simulator.config.AppConfig;
import com.adkernel.simulator.http.ApiResult;
import com.adkernel.simulator.http.RtaClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回源合并实证（DoD k05）：500 并发同 key 冷查询，源层只收到 1 次。
 *
 * <p>
 * Caffeine AsyncCache 的 singleflight 语义：同 key 并发未命中时 loader 只执行一次，
 * 其余请求共享同一 Future（Go 需要 singleflight 库，Java 的 LoadingCache.get 内在语义就是它）。
 * 冷用户取扩展池（i=450_001，流量永不触及——L1/L2 天然冷）。断言口径取基线差值，不要求独占环境。
 * </p>
 */
public final class MergeCheck {

  private MergeCheck() {
  }

  private static final int CONCURRENCY = 500;
  private static final Pattern RPC_COUNT = Pattern.compile("\"featureRpcCount\":(\\d+)");
  private static final Pattern SOURCE_QUERIES = Pattern.compile("\"queries\":(\\d+)");

  public static int run(AppConfig cfg) {
    try (RtaClient client = new RtaClient(2_000)) {
      String url = cfg.endpoints().decisionUrl();
      String secret = cfg.auth().secretKey();
      String userId = UserIds.userId(450_001); // 扩展池冷用户：非黑名单(450001%100!=0)、有画像(!=7)
      String rtaStats = originOf(cfg.endpoints().decisionUrl()) + "/internal/stats";
      String featureStats = cfg.endpoints().featureAdminUrl() + "/admin/stats";

      long rpcBefore = extract(client, rtaStats, RPC_COUNT);
      long srcBefore = extract(client, featureStats, SOURCE_QUERIES);

      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch done = new CountDownLatch(CONCURRENCY);
      AtomicInteger ok = new AtomicInteger();
      AtomicInteger targetTrue = new AtomicInteger();
      for (int i = 0; i < CONCURRENCY; i++) {
        Thread.ofVirtual().start(() -> {
          try {
            start.await();
            long now = System.currentTimeMillis();
            String rid = "req-merge-" + UUID_RANDOM();
            String sign = HmacSigner.sign(rid, now, userId, secret);
            RtaRequest req = new RtaRequest(rid, now, "cmp-001", userId, "slot-feed-1001", "feed", sign);
            ApiResult r = client.postJson(url, req);
            RtaResponse body = client.parseDecision(r);
            if (r.httpOk() && body != null && body.code() == 0) {
              ok.incrementAndGet();
              if (body.target()) {
                targetTrue.incrementAndGet();
              }
            }
          } catch (Exception ignore) {
            // 单请求失败由 ok 计数反映
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      done.await();

      long rpcAfter = extract(client, rtaStats, RPC_COUNT);
      long srcAfter = extract(client, featureStats, SOURCE_QUERIES);
      long rpcDelta = rpcAfter - rpcBefore;
      long srcDelta = srcAfter - srcBefore;

      System.out.printf("[mergecheck] concurrency=%d coldUser=%s%n", CONCURRENCY, userId);
      System.out.printf(
          "[mergecheck] decisions ok=%d target=true=%d (early arrivals degrade no_profile by design: 5ms soft budget < cold load)%n",
          ok.get(), targetTrue.get());
      System.out.printf("[mergecheck] RTA feature RPC delta   = %d (expect 1)%n", rpcDelta);
      System.out.printf("[mergecheck] feature source queries  = %d (expect 1)%n", srcDelta);

      // 手册 k05 口径：源层只收到 1 次查询（singleflight 生效）+ 全部请求 200/code0。
      // target=true 不作断言——冷装载完成前到达的请求按 5ms 软预算合法降级（M1 降级链设计）。
      boolean pass = rpcDelta == 1 && srcDelta == 1 && ok.get() == CONCURRENCY;
      System.out.println("[mergecheck] overall: " + (pass ? "PASS" : "FAIL"));
      return pass ? 0 : 1;
    } catch (Exception e) {
      System.err.println("[mergecheck] error: " + e.getMessage());
      return 1;
    }
  }

  private static long extract(RtaClient client, String statsUrl, Pattern p) {
    ApiResult r = client.get(statsUrl);
    if (!r.httpOk() || r.body() == null) {
      throw new IllegalStateException("stats fetch failed: http=" + r.status());
    }
    Matcher m = p.matcher(r.body());
    if (!m.find()) {
      throw new IllegalStateException("pattern not found in stats: " + p.pattern());
    }
    return Long.parseLong(m.group(1));
  }

  private static String UUID_RANDOM() {
    return java.util.UUID.randomUUID().toString();
  }

  /** http://host:port/api/... → http://host:port */
  private static String originOf(String url) {
    int idx = url.indexOf("://");
    int slash = url.indexOf('/', idx + 3);
    return slash > 0 ? url.substring(0, slash) : url;
  }
}
