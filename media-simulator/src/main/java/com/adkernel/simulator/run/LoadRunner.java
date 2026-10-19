package com.adkernel.simulator.run;

import com.adkernel.common.protocol.BillRecord;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.user.UserPool;
import com.adkernel.simulator.billing.BillGenerator;
import com.adkernel.simulator.config.AppConfig;
import com.adkernel.simulator.config.FunnelConfig;
import com.adkernel.simulator.config.TrafficConfig;
import com.adkernel.simulator.funnel.FunnelEngine;
import com.adkernel.simulator.gen.RequestContext;
import com.adkernel.simulator.gen.RequestGenerator;
import com.adkernel.simulator.http.ApiResult;
import com.adkernel.simulator.http.RtaClient;
import com.adkernel.simulator.judge.Judge;
import com.adkernel.simulator.metrics.DistributionTracker;
import com.adkernel.simulator.metrics.PerSecondRecorder;
import com.adkernel.simulator.report.ReportWriter;
import com.adkernel.simulator.trace.TraceRecorder;
import com.adkernel.simulator.traffic.QpsCurve;
import com.adkernel.simulator.traffic.ZipfSampler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * 压测运行器：QPS 曲线追帧调度（期望发射数 = 分段精确积分），每请求一个虚拟线程。
 * 裁判先于运动员：每轮结束立即出 Judge 快照，整运行出账单 + 报告 + 延迟基线。
 */
public final class LoadRunner {

  private final AppConfig app;
  private final TrafficConfig traffic;
  private final FunnelConfig funnelCfg;
  private final String profileName;

  public LoadRunner(AppConfig app, TrafficConfig traffic, FunnelConfig funnelCfg, String profileName) {
    this.app = app;
    this.traffic = traffic;
    this.funnelCfg = funnelCfg;
    this.profileName = profileName;
  }

  public int run() throws Exception {
    TrafficConfig.Profile profile = traffic.profiles().get(profileName);
    if (profile == null) {
      throw new IllegalArgumentException("unknown profile: " + profileName
          + ", available: " + traffic.profiles().keySet());
    }
    QpsCurve curve = new QpsCurve(profile);
    String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    Path runDir = Path.of(app.dataDir(), "runs", runId);
    Files.createDirectories(runDir);

    UserPool pool = new UserPool(app.userPool().size());
    ZipfSampler sampler = new ZipfSampler(pool.size(), app.userPool().zipfS());
    RequestGenerator generator = new RequestGenerator(pool, sampler, app);
    RtaClient client = new RtaClient(app.clientTimeoutMs());
    TraceRecorder trace = new TraceRecorder(runDir.resolve("traces.jsonl"));
    FunnelEngine funnel = new FunnelEngine(client, app, funnelCfg, trace);
    DistributionTracker distribution = new DistributionTracker(pool.size());
    LongAdder participate = new LongAdder();

    System.out.printf("[sim] run=%s profile=%s rounds=%d curve=%.0fs (warmup %ds) pool=%d zipfS=%.2f%n",
        runId, profileName, profile.rounds(), curve.totalSec(), curve.warmupSec(),
        pool.size(), app.userPool().zipfS());

    List<RoundResult> rounds = new ArrayList<>();
    for (int r = 1; r <= profile.rounds(); r++) {
      Path roundDir = runDir.resolve("round-" + r);
      PerSecondRecorder recorder = new PerSecondRecorder(curve, roundDir.resolve("metrics.jsonl"));
      Judge judge = new Judge();
      AtomicInteger inFlight = new AtomicInteger();
      long start = System.nanoTime();
      long launched = 0;
      long lastLog = System.currentTimeMillis();

      while (true) {
        double elapsed = (System.nanoTime() - start) / 1e9;
        if (elapsed >= curve.totalSec()) {
          break;
        }
        long due = (long) curve.expectedCount(elapsed);
        int cap = inFlightCap(curve.qpsAt(elapsed));
        while (launched < due && inFlight.get() < cap) {
          launched++;
          inFlight.incrementAndGet();
          Thread.ofVirtual().start(() -> {
            try {
              RequestContext ctx = generator.next();
              long t0 = System.nanoTime();
              ApiResult r1 = client.postJson(app.endpoints().decisionUrl(), ctx.request());
              long lat = System.nanoTime() - t0;
              RtaResponse parsed = r1.httpOk() ? client.parseDecision(r1) : null;
              boolean err = !r1.httpOk() || parsed == null;
              judge.evaluate(ctx, r1, parsed);
              recorder.record(lat, err, r1.timedOut());
              distribution.record(ctx.poolIndex(), ctx.fakeUser());
              if (parsed != null && parsed.target()) {
                participate.increment();
              }
              funnel.onDecision(ctx, parsed);
            } catch (Throwable t) {
              System.err.println("[sim] request task failed: " + t);
            } finally {
              inFlight.decrementAndGet();
            }
          });
        }
        if (System.currentTimeMillis() - lastLog > 10_000) {
          lastLog = System.currentTimeMillis();
          System.out.printf("[sim] round %d/%d elapsed=%.0fs launched=%d inFlight=%d p99=%.2fms%n",
              r, profile.rounds(), elapsed, launched, inFlight.get(),
              recorder.overall().p(0.99));
        }
        LockSupport.parkNanos(100_000);
      }

      // 排空在途请求（决策 + 漏斗回传）
      long drainDeadline = System.currentTimeMillis() + 5_000;
      while (inFlight.get() > 0 && System.currentTimeMillis() < drainDeadline) {
        Thread.sleep(20);
      }
      recorder.close();
      rounds.add(new RoundResult(judge, recorder.overall(), recorder.byPhase(),
          roundDir.resolve("metrics.jsonl")));
      System.out.printf("[sim] round %d done: count=%d p99=%.2fms timeouts=%d blacklistViolations=%d%n",
          r, judge.total(), recorder.overall().p(0.99), judge.timeouts(), judge.blacklistViolations());
    }

    // 账单：整运行聚合（T+1 模拟，协议 03）
    List<BillRecord> bills = BillGenerator.generate(funnel, LocalDate.now());
    Path billFile = runDir.resolve("bill-" + LocalDate.now() + ".json");
    BillGenerator.write(billFile, bills);

    // 延迟基线：中位轮（按 P99，M5 纪律：预热已剔除）→ canonical baseline-m0.jsonl（仅 acceptance）
    Path baselineFile = null;
    if ("acceptance".equals(profileName)) {
      RoundResult median = rounds.stream()
          .sorted(Comparator.comparingDouble(rr -> rr.overall().p(0.99)))
          .toList().get(rounds.size() / 2);
      baselineFile = Path.of(app.dataDir(), "baseline", "baseline-m0.jsonl");
      copyNonWarmup(median.metricsFile(), baselineFile);
    }

    trace.close();
    client.close();

    ReportWriter.Context ctx = new ReportWriter.Context(
        runId, profileName, "fault".equals(profileName), app, profile, curve, funnelCfg,
        rounds, funnel, participate.sum(), distribution, sampler, bills, billFile,
        trace.firstTraceLine(), baselineFile);
    boolean pass = ReportWriter.write(runDir, ctx);

    System.out.printf("[sim] ===== RUN SUMMARY (profile=%s) =====%n", profileName);
    for (int i = 0; i < rounds.size(); i++) {
      RoundResult rr = rounds.get(i);
      System.out.printf(
          "[sim] round %d: count=%d avgQps=%.0f p50=%.2fms p99=%.2fms err=%.4f%% timeout=%.4f%% violations=%d%n",
          i + 1, rr.judge().total(),
          rr.overall().count() / Math.max(1, curve.totalSec() - curve.warmupSec()),
          rr.overall().p(0.50), rr.overall().p(0.99),
          rr.judge().errRate() * 100, rr.judge().timeoutRate() * 100,
          rr.judge().blacklistViolations());
    }
    if (distribution.top20Measurable()) {
      System.out.printf("[sim] top20 share: %.3f (expected %.3f)%n",
          distribution.measuredTop20Share(), sampler.expectedTop20Share());
    } else {
      System.out.printf("[sim] top20 share: n/a (distinct users %d < 20%% of pool, low-volume profile)%n",
          distribution.observedUsers());
    }
    System.out.printf("[sim] funnel: win=%.4f(cfg %.2f) ctr=%.4f(cfg %.2f) cvr=%.4f(cfg %.2f)%n",
        funnel.observedWinRate(participate.sum()), funnelCfg.winRate(),
        funnel.observedCtr(), funnelCfg.ctr(),
        funnel.observedCvr(), funnelCfg.cvr());
    for (BillRecord b : bills) {
      System.out.printf("[sim] bill: %s cost=%.2f impressions=%d clicks=%d%n",
          b.campaignId(), b.totalCost(), b.impressions(), b.clicks());
    }
    System.out.printf("[sim] report: %s%n", runDir.resolve("report.md").toAbsolutePath());
    System.out.printf("[sim] verdict: %s%n", pass ? "PASS" : "FAIL");
    return pass ? 0 : 1;
  }

  /**
   * 在途上限（背压限流）：当前相位 QPS 的 2 倍为硬性上限，最低不低于 1000。
   * 追帧调度只看期望发射数会导致完成率塌缩时在途无限堆积（JDK HttpClient 单 selector
   * 追不上发射速率，曾观测 inFlight 线性涨到 250 万引发拥塞崩溃）——只在途低于上限才发射，
   * 服务端/客户端过载时自动降速，恢复后追帧补齐。
   */
  private int inFlightCap(double qps) {
    return (int) Math.max(qps * 2, 1000);
  }

  /** 剔除预热行后拷贝（基线口径：只保留 warmup=false 的秒） */
  private static void copyNonWarmup(Path metricsFile, Path target) throws Exception {
    Files.createDirectories(target.getParent());
    List<String> kept = new ArrayList<>();
    for (String line : Files.readAllLines(metricsFile)) {
      if (line.contains("\"warmup\":false")) {
        kept.add(line);
      }
    }
    Files.write(target, kept);
  }
}
