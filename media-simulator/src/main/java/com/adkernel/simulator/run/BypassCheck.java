package com.adkernel.simulator.run;

import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.user.UserPool;
import com.adkernel.simulator.config.AppConfig;
import com.adkernel.simulator.gen.RequestContext;
import com.adkernel.simulator.gen.RequestGenerator;
import com.adkernel.simulator.http.ApiResult;
import com.adkernel.simulator.http.RtaClient;
import com.adkernel.simulator.traffic.ZipfSampler;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缓存价值对照实验（DoD k10 / 12ms→2ms 叙事实测）：
 * 同一流量两个纪元——cached（三级缓存路径，先行——复用压测期预热好的 L1）vs
 * bypass（L1 旁路 + 特征服务全层旁路 = 每次直查源）。
 *
 * <p>
 * 延迟观测口径：RTA /internal/stats 的 featureLatency 环形样本（16384 条滚动覆盖），
 * 每纪元流量 5 万+ 样本完整覆写环——读数即该纪元尾段稳态分布。
 * 命中率口径（DoD ≥80%）：真实用户流量命中率——伪造 ID 按 D4 纪律永不入 L1
 * （10% 假流量拉低原始命中率是设计内行为，原始上限 = 90%×80% = 72%），
 * 调整口径 = hits / (lookups × (1-fakeRatio))。两个数字都落盘。
 * </p>
 */
public final class BypassCheck {

  private static final int QPS = 2000;
  private static final int CACHED_SEC = 35; // 7 万样本：环覆写 + 稳态
  private static final int BYPASS_SEC = 25; // 5 万样本：环覆写

  private static final Pattern P50 = Pattern.compile("\"featureLatencyP50Ms\":([0-9.]+)");
  private static final Pattern P99 = Pattern.compile("\"featureLatencyP99Ms\":([0-9.]+)");
  private static final Pattern HITS = Pattern.compile("\"hitCount\":(\\d+)");
  private static final Pattern MISS = Pattern.compile("\"missCount\":(\\d+)");

  public static int run(AppConfig cfg) {
    UserPool pool = new UserPool(cfg.userPool().size());
    ZipfSampler sampler = new ZipfSampler(pool.size(), cfg.userPool().zipfS());
    RequestGenerator generator = new RequestGenerator(pool, sampler, cfg);
    String cacheAdmin = originOf(cfg.endpoints().decisionUrl()) + "/internal/admin/cache";
    String injectAdmin = cfg.endpoints().featureAdminUrl() + "/admin/inject";
    String rtaStats = originOf(cfg.endpoints().decisionUrl()) + "/internal/stats";

    try (RtaClient client = new RtaClient(cfg.clientTimeoutMs());
        RtaClient admin = new RtaClient(5_000)) {
      // 纪元 A（先跑，吃压测期预热）：cached 三级缓存路径
      long hitBefore = extract(client, rtaStats, HITS);
      long missBefore = extract(client, rtaStats, MISS);
      postAdmin(admin, injectAdmin, Map.of("sourceLatencyMs", 5, "l2HangMs", 0, "bypassLayers", false));
      System.out.println("[bypasscheck] era A: cached 3-tier path, " + CACHED_SEC + "s @ " + QPS + " QPS");
      Era a = runEra(client, generator, cfg, CACHED_SEC, "cached");
      long hitDelta = extract(client, rtaStats, HITS) - hitBefore;
      long missDelta = extract(client, rtaStats, MISS) - missBefore;

      // 纪元 B（后跑）：bypass——L1 旁路（切模式即 invalidateAll）+ 特征服务全层旁路 = 直查源
      postAdmin(admin, cacheAdmin, Map.of("bypass", true));
      postAdmin(admin, injectAdmin, Map.of("sourceLatencyMs", 5, "l2HangMs", 0, "bypassLayers", true));
      System.out
          .println("[bypasscheck] era B: bypass all layers (direct source), " + BYPASS_SEC + "s @ " + QPS + " QPS");
      Era b = runEra(client, generator, cfg, BYPASS_SEC, "bypass");

      // 恢复正常模式（不留实验开关）
      postAdmin(admin, cacheAdmin, Map.of("bypass", false));
      postAdmin(admin, injectAdmin, Map.of("sourceLatencyMs", 5, "l2HangMs", 0, "bypassLayers", false));

      long lookups = hitDelta + missDelta;
      double rawHitRate = lookups == 0 ? 0 : (double) hitDelta / lookups;
      double realLookups = lookups * (1.0 - cfg.userPool().fakeRatio());
      double realHitRate = realLookups <= 0 ? 0 : Math.min(1.0, hitDelta / realLookups);

      System.out.println();
      System.out.println("[bypasscheck] ===== SUMMARY =====");
      System.out.printf("[bypasscheck] %-8s P50=%.3fms P99=%.3fms ok=%d err=%d timeout=%d%n",
          "cached", a.p50Ms(), a.p99Ms(), a.ok(), a.err(), a.timeout());
      System.out.printf("[bypasscheck] %-8s P50=%.3fms P99=%.3fms ok=%d err=%d timeout=%d%n",
          "bypass", b.p50Ms(), b.p99Ms(), b.ok(), b.err(), b.timeout());
      System.out.printf("[bypasscheck] cached era L1 hitRate(raw) = %.1f%% (hits=%d misses=%d)%n",
          rawHitRate * 100, hitDelta, missDelta);
      System.out.printf("[bypasscheck] cached era L1 hitRate(real-user) = %.1f%% (fakeRatio=%.0f%% excluded by D4)%n",
          realHitRate * 100, cfg.userPool().fakeRatio() * 100);
      System.out.printf("[bypasscheck] speedup: P50 x%.0f, P99 x%.0f%n",
          a.p50Ms() == 0 ? 0 : b.p50Ms() / Math.max(a.p50Ms(), 0.0001),
          a.p99Ms() == 0 ? 0 : b.p99Ms() / Math.max(a.p99Ms(), 0.0001));

      boolean pass = a.err() == 0 && a.timeout() == 0 && b.err() == 0 && b.timeout() == 0
          && a.p50Ms() < 0.5 // L1 命中主导（P50 = 命中路径 <0.1ms 量级）
          && a.p99Ms() < b.p99Ms() // 缓存路径 P99 显著优于直查
          && realHitRate >= 0.78; // DoD ≥80%，留 2pp 环境噪声余量
      System.out.println("[bypasscheck] overall: " + (pass ? "PASS" : "FAIL")
          + " (criteria: no err/timeout, cached P50<0.5ms, cached P99<bypass P99, real-user hitRate>=78%)");
      return pass ? 0 : 1;
    } catch (Exception e) {
      System.err.println("[bypasscheck] error: " + e.getMessage());
      e.printStackTrace();
      return 1;
    }
  }

  private record Era(double p50Ms, double p99Ms, long ok, long err, long timeout) {
  }

  /** 单纪元流量：追帧发射 + 每请求虚拟线程（同 cachefault 口径） */
  private static Era runEra(RtaClient client, RequestGenerator generator,
      AppConfig cfg, int sec, String label) throws InterruptedException {
    LongAdder ok = new LongAdder();
    LongAdder err = new LongAdder();
    LongAdder timeout = new LongAdder();
    CountDownLatch finished = new CountDownLatch(1);
    AtomicInteger inFlight = new AtomicInteger();

    Thread launcher = Thread.ofVirtual().start(() -> {
      long start = System.nanoTime();
      long launched = 0;
      while (true) {
        double elapsed = (System.nanoTime() - start) / 1e9;
        if (elapsed >= sec) {
          break;
        }
        long due = (long) (elapsed * QPS);
        int cap = QPS * 2;
        while (launched < due && inFlight.get() < cap) {
          launched++;
          inFlight.incrementAndGet();
          Thread.ofVirtual().start(() -> {
            try {
              RequestContext ctx = generator.next();
              ApiResult r = client.postJson(cfg.endpoints().decisionUrl(), ctx.request());
              RtaResponse body = r.httpOk() ? client.parseDecision(r) : null;
              if (r.timedOut()) {
                timeout.increment();
              } else if (!r.httpOk() || body == null || body.code() != 0) {
                err.increment();
              } else {
                ok.increment();
              }
            } catch (Throwable ignore) {
              err.increment();
            } finally {
              inFlight.decrementAndGet();
            }
          });
        }
        LockSupport.parkNanos(1_000_000);
      }
      long deadline = System.currentTimeMillis() + 5_000;
      while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
        try {
          Thread.sleep(20);
        } catch (InterruptedException e) {
          break;
        }
      }
      finished.countDown();
    });
    finished.await();

    // 环形延迟样本已被本纪元覆写（样本数 > 环容量），读数即本纪元稳态分布
    String stats = getStats(client, originOf(cfg.endpoints().decisionUrl()) + "/internal/stats");
    double p50 = extractDouble(stats, P50);
    double p99 = extractDouble(stats, P99);
    System.out.printf("[bypasscheck] %-8s done: ok=%d err=%d timeout=%d P50=%.3fms P99=%.3fms%n",
        label, ok.sum(), err.sum(), timeout.sum(), p50, p99);
    return new Era(p50, p99, ok.sum(), err.sum(), timeout.sum());
  }

  private static void postAdmin(RtaClient client, String url, Map<String, Object> payload) {
    ApiResult r = client.postJson(url, payload);
    if (!r.httpOk()) {
      throw new IllegalStateException("admin post failed: http=" + r.status() + " url=" + url);
    }
  }

  private static String getStats(RtaClient client, String url) {
    ApiResult r = client.get(url);
    if (!r.httpOk() || r.body() == null) {
      throw new IllegalStateException("stats fetch failed: http=" + r.status());
    }
    return r.body();
  }

  private static long extract(RtaClient client, String statsUrl, Pattern p) {
    return (long) extractDouble(getStats(client, statsUrl), p);
  }

  private static double extractDouble(String body, Pattern p) {
    Matcher m = p.matcher(body);
    if (!m.find()) {
      throw new IllegalStateException("pattern not found: " + p.pattern());
    }
    return Double.parseDouble(m.group(1));
  }

  /** http://host:port/api/... → http://host:port */
  private static String originOf(String url) {
    int idx = url.indexOf("://");
    int slash = url.indexOf('/', idx + 3);
    return slash > 0 ? url.substring(0, slash) : url;
  }
}
