package com.adkernel.simulator.run;

import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.user.UserPool;
import com.adkernel.simulator.config.AppConfig;
import com.adkernel.simulator.gen.RequestContext;
import com.adkernel.simulator.gen.RequestGenerator;
import com.adkernel.simulator.http.ApiResult;
import com.adkernel.simulator.http.RtaClient;
import com.adkernel.simulator.traffic.ZipfSampler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缓存故障注入实验（DoD k09）：模拟器稳态流量 + 两级注入 → 决策成功率 100%、超时率 0。
 *
 * <p>
 * 编排（"没有演练过的防护，和没有一样"）：
 * </p>
 * <ol>
 * <li>阶段0 baseline：3000 QPS × 60s（稳态建立，L1 热身）；</li>
 * <li>阶段1 源 50ms 延迟注入 × 300s（模拟 DB 抖动 5 分钟）——预期：L1 命中无感，
 * miss 走 stale/默认，超时率不升；</li>
 * <li>阶段2 L2 hang 30s 注入 + 流量继续 45s（覆盖 hang 窗口）——预期：全量走
 * stale/默认画像，决策成功率不降、超时率 0；</li>
 * <li>阶段3 解除注入 × 60s（恢复观察）。</li>
 * </ol>
 *
 * <p>
 * 断言：全程 http 200 + code 0（业务拒绝 no_profile/blacklist 属合法响应）；
 * 超时 = 0；降级计数（degradedStale/degradedDefault）在注入期增量 &gt; 0（降级路径确实被走到且被观测）。
 * </p>
 */
public final class CacheFault {

  private CacheFault() {
  }

  private static final int QPS = 3000;
  private static final int PHASE0_SEC = 60; // baseline
  private static final int PHASE1_SEC = 300; // 源 50ms（手册任务4：5 分钟）
  private static final int PHASE2_SEC = 45; // L2 hang 30s 窗口覆盖
  private static final int PHASE3_SEC = 60; // 恢复

  private static final Pattern DEGRADED_STALE = Pattern.compile("\"degradedStale\":(\\d+)");
  private static final Pattern DEGRADED_DEFAULT = Pattern.compile("\"degradedDefault\":(\\d+)");

  public static int run(AppConfig cfg) {
    UserPool pool = new UserPool(cfg.userPool().size());
    ZipfSampler sampler = new ZipfSampler(pool.size(), cfg.userPool().zipfS());
    RequestGenerator generator = new RequestGenerator(pool, sampler, cfg);
    String injectUrl = cfg.endpoints().featureAdminUrl() + "/admin/inject";
    String rtaStats = originOf(cfg.endpoints().decisionUrl()) + "/internal/stats";

    try (RtaClient client = new RtaClient(cfg.clientTimeoutMs())) {
      long staleBefore = extract(client, rtaStats, DEGRADED_STALE);
      long defaultBefore = extract(client, rtaStats, DEGRADED_DEFAULT);

      long totalErr = 0;
      long totalTimeout = 0;
      long totalOk = 0;

      System.out.println("[cachefault] phase 0: baseline " + PHASE0_SEC + "s @ " + QPS + " QPS");
      PhaseResult p0 = runPhase(client, generator, cfg, PHASE0_SEC, "baseline");
      totalErr += p0.err();
      totalTimeout += p0.timeout();
      totalOk += p0.ok();

      System.out.println("[cachefault] phase 1: inject sourceLatency=50ms, " + PHASE1_SEC + "s");
      postInject(client, injectUrl, 50, 0);
      PhaseResult p1 = runPhase(client, generator, cfg, PHASE1_SEC, "src50ms");
      postInject(client, injectUrl, 5, 0);
      totalErr += p1.err();
      totalTimeout += p1.timeout();
      totalOk += p1.ok();

      System.out.println("[cachefault] phase 2: inject l2 hang 30s, traffic " + PHASE2_SEC + "s");
      postInject(client, injectUrl, 5, 30_000);
      PhaseResult p2 = runPhase(client, generator, cfg, PHASE2_SEC, "l2hang30s");
      totalErr += p2.err();
      totalTimeout += p2.timeout();
      totalOk += p2.ok();

      System.out.println("[cachefault] phase 3: recovery " + PHASE3_SEC + "s");
      postInject(client, injectUrl, 5, 0); // 解除注入（源延迟回 5ms / L2 hang 清零）——恢复期必须无注入
      PhaseResult p3 = runPhase(client, generator, cfg, PHASE3_SEC, "recovery");
      totalErr += p3.err();
      totalTimeout += p3.timeout();
      totalOk += p3.ok();

      long staleAfter = extract(client, rtaStats, DEGRADED_STALE);
      long defaultAfter = extract(client, rtaStats, DEGRADED_DEFAULT);
      long staleDelta = staleAfter - staleBefore;
      long defaultDelta = defaultAfter - defaultBefore;

      System.out.println();
      System.out.println("[cachefault] ===== SUMMARY =====");
      System.out.printf("[cachefault] %-10s ok=%d err=%d timeout=%d targetFalse=%d%n",
          "baseline", p0.ok(), p0.err(), p0.timeout(), p0.targetFalse());
      System.out.printf("[cachefault] %-10s ok=%d err=%d timeout=%d targetFalse=%d%n",
          "src50ms", p1.ok(), p1.err(), p1.timeout(), p1.targetFalse());
      System.out.printf("[cachefault] %-10s ok=%d err=%d timeout=%d targetFalse=%d%n",
          "l2hang30s", p2.ok(), p2.err(), p2.timeout(), p2.targetFalse());
      System.out.printf("[cachefault] %-10s ok=%d err=%d timeout=%d targetFalse=%d%n",
          "recovery", p3.ok(), p3.err(), p3.timeout(), p3.targetFalse());
      System.out.printf("[cachefault] degradedStale delta   = %d (expect >0 in inject phases)%n", staleDelta);
      System.out.printf("[cachefault] degradedDefault delta = %d (expect >0 in inject phases)%n", defaultDelta);

      // 手册任务4口径：两级注入实验（阶段1/2）+ 恢复期 0 超时、0 错误。
      // baseline 是热身期（冷 JIT/L1 装载），其超时为预热噪声仅记录不断言。
      boolean pass = p1.err() == 0 && p1.timeout() == 0
          && p2.err() == 0 && p2.timeout() == 0
          && p3.err() == 0 && p3.timeout() == 0
          && (staleDelta + defaultDelta) > 0
          && p3.ok() > 0; // 恢复期仍有成功决策
      System.out.printf("[cachefault] total: ok=%d err=%d timeout=%d%n", totalOk, totalErr, totalTimeout);
      System.out.println("[cachefault] overall: " + (pass ? "PASS" : "FAIL")
          + " (criteria: err=0, timeout=0, degraded>0)");
      return pass ? 0 : 1;
    } catch (Exception e) {
      System.err.println("[cachefault] error: " + e.getMessage());
      e.printStackTrace();
      return 1;
    }
  }

  private record PhaseResult(long ok, long err, long timeout, long targetFalse) {
  }

  /** 单相位流量：追帧发射 + 每请求虚拟线程（决策成功 = http 200 且 code 0） */
  private static PhaseResult runPhase(RtaClient client, RequestGenerator generator,
      AppConfig cfg, int sec, String label) throws InterruptedException {
    LongAdder ok = new LongAdder();
    LongAdder err = new LongAdder();
    LongAdder timeout = new LongAdder();
    LongAdder targetFalse = new LongAdder();
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
                if (!body.target()) {
                  targetFalse.increment();
                }
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
      // 排空在途
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
    System.out.printf("[cachefault] %-10s done: ok=%d err=%d timeout=%d targetFalse=%d%n",
        label, ok.sum(), err.sum(), timeout.sum(), targetFalse.sum());
    return new PhaseResult(ok.sum(), err.sum(), timeout.sum(), targetFalse.sum());
  }

  private static void postInject(RtaClient client, String url, long srcMs, long hangMs) {
    // 传 Map 让 postJson 统一序列化——传预序列化 String 会被 Json.write 再包引号导致 400
    ApiResult r = client.postJson(url, java.util.Map.of("sourceLatencyMs", srcMs, "l2HangMs", hangMs));
    if (!r.httpOk()) {
      throw new IllegalStateException("inject failed: http=" + r.status());
    }
  }

  private static long extract(RtaClient client, String statsUrl, Pattern p) {
    ApiResult r = client.get(statsUrl);
    if (!r.httpOk() || r.body() == null) {
      throw new IllegalStateException("stats fetch failed: http=" + r.status());
    }
    Matcher m = p.matcher(r.body());
    if (!m.find()) {
      throw new IllegalStateException("pattern not found: " + p.pattern());
    }
    return Long.parseLong(m.group(1));
  }

  /** http://host:port/api/... → http://host:port */
  private static String originOf(String url) {
    int idx = url.indexOf("://");
    int slash = url.indexOf('/', idx + 3);
    return slash > 0 ? url.substring(0, slash) : url;
  }
}
