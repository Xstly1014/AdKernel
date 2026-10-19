package com.adkernel.simulator.report;

import com.adkernel.common.protocol.BillRecord;
import com.adkernel.simulator.config.AppConfig;
import com.adkernel.simulator.config.FunnelConfig;
import com.adkernel.simulator.config.TrafficConfig;
import com.adkernel.simulator.funnel.FunnelEngine;
import com.adkernel.simulator.metrics.DistributionTracker;
import com.adkernel.simulator.metrics.PerSecondRecorder;
import com.adkernel.simulator.run.RoundResult;
import com.adkernel.simulator.traffic.QpsCurve;
import com.adkernel.simulator.traffic.ZipfSampler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 裁判报告（Markdown，UTF-8）：延迟分布 / 分相分桶（洪峰独立）/ 断言判罚 /
 * 漏斗勾稽 / 幂律分布 / requestId 贯穿示例 / 账单 / DoD 对照。
 */
public final class ReportWriter {

  /** 报告上下文（聚合一次运行的全部证据） */
  public record Context(
      String runId,
      String profileName,
      boolean faultExpected, // fault 演示模式：黑名单违规 > 0 才是断言生效实证
      AppConfig app,
      TrafficConfig.Profile profile,
      QpsCurve curve,
      FunnelConfig funnelCfg,
      List<RoundResult> rounds,
      FunnelEngine funnel,
      long participateCount,
      DistributionTracker distribution,
      ZipfSampler sampler,
      List<BillRecord> bills,
      Path billFile,
      String traceSample,
      Path baselineFile) {
  }

  private ReportWriter() {
  }

  /** 写报告；返回总判定 PASS/FAIL */
  public static boolean write(Path runDir, Context c) throws Exception {
    StringBuilder md = new StringBuilder();

    double measureSec = Math.max(1, c.curve().totalSec() - c.curve().warmupSec());
    double targetAvgQps = (c.curve().expectedCount(c.curve().totalSec())
        - c.curve().expectedCount(c.curve().warmupSec())) / measureSec;

    // ---- 判定计算 ----
    long totalViolations = c.rounds().stream().mapToLong(r -> r.judge().blacklistViolations()).sum();
    double minAvgQps = c.rounds().stream()
        .mapToLong(r -> r.overall().count()).min().orElse(0) / measureSec;
    boolean qpsPass = c.rounds().stream().allMatch(r -> r.overall().count() / measureSec >= targetAvgQps * 0.95);
    boolean timeoutPass = c.rounds().stream().allMatch(r -> r.judge().timeoutRate() <= 0.01);
    boolean errPass = c.rounds().stream().allMatch(r -> r.judge().errRate() <= 0.01);
    boolean violationPass = c.faultExpected() ? totalViolations > 0 : totalViolations == 0;
    double obsWin = c.funnel().observedWinRate(c.participateCount());
    double obsCtr = c.funnel().observedCtr();
    double obsCvr = c.funnel().observedCvr();
    boolean winPass = withinRelSamples(obsWin, c.funnelCfg().winRate(), 0.03, c.participateCount());
    boolean ctrPass = withinRelSamples(obsCtr, c.funnelCfg().ctr(), 0.03, c.funnel().exposures());
    boolean cvrPass = withinRelSamples(obsCvr, c.funnelCfg().cvr(), 0.03, c.funnel().clicks());
    boolean funnelInvariant = c.funnel().exposures() >= c.funnel().clicks()
        && c.funnel().clicks() >= c.funnel().converts();
    boolean distMeasurable = c.distribution().top20Measurable();
    boolean distPass = !distMeasurable
        || (c.distribution().measuredTop20Share() >= 0.75
            && c.distribution().measuredTop20Share() <= 0.85);
    boolean billPass = !c.bills().isEmpty() && c.bills().stream()
        .allMatch(b -> b.clicks() <= b.impressions());
    boolean tracePass = c.traceSample() != null;

    md.append("# M0 裁判报告 · ").append(c.runId()).append("\n\n");
    md.append("> profile = **").append(c.profileName()).append("**  \n");
    md.append("> 判定模式 = ").append(c.faultExpected()
        ? "fault 演示（黑名单违规 > 0 为断言生效实证，DoD k07）"
        : "常规（黑名单违规必须为 0）").append("\n\n");

    // ---- 1. 运行配置 ----
    md.append("## 1. 运行配置\n\n");
    md.append("| 项 | 值 |\n|---|---|\n");
    md.append("| 轮数 × 曲线 | ").append(c.profile().rounds()).append(" × ")
        .append(String.format("%.0f", c.curve().totalSec())).append("s（预热 ")
        .append(c.curve().warmupSec()).append("s）|\n");
    md.append("| QPS 曲线 | ").append(curveDesc(c.profile())).append(" |\n");
    md.append("| 用户池 | size=").append(c.app().userPool().size())
        .append(", zipfS=").append(c.app().userPool().zipfS())
        .append(", fakeRatio=").append(c.app().userPool().fakeRatio()).append(" |\n");
    md.append("| 漏斗配置 | winRate=").append(c.funnelCfg().winRate())
        .append(", ctr=").append(c.funnelCfg().ctr())
        .append(", cvr=").append(c.funnelCfg().cvr())
        .append(", cpc∈[").append(c.funnelCfg().cpcMin()).append(",")
        .append(c.funnelCfg().cpcMax()).append("] |\n");
    md.append("| 客户端超时 | ").append(c.app().clientTimeoutMs()).append("ms（媒体红线） |\n\n");

    // ---- 2. 每轮延迟与吞吐 ----
    md.append("## 2. 每轮延迟与吞吐（预热后，客户端观测口径）\n\n");
    md.append(
        "| 轮 | 请求数 | 平均QPS | P50 | P90 | P99 | P999 | max | err率 | 超时率 |\n|---|---|---|---|---|---|---|---|---|---|\n");
    for (int i = 0; i < c.rounds().size(); i++) {
      RoundResult r = c.rounds().get(i);
      PerSecondRecorder.Stats s = r.overall();
      md.append(String.format("| %d | %d | %.0f | %.2f | %.2f | %.2f | %.2f | %.2f | %.4f%% | %.4f%% |%n",
          i + 1, s.count(), s.count() / measureSec, s.p(0.50), s.p(0.90), s.p(0.99),
          s.p(0.999), s.maxMs(), r.judge().errRate() * 100, r.judge().timeoutRate() * 100));
    }
    md.append("\n目标平均 QPS（曲线积分/测量窗口）= ").append(String.format("%.0f", targetAvgQps))
        .append("；实测最低轮平均 = ").append(String.format("%.0f", minAvgQps)).append("\n\n");

    // ---- 3. 分相延迟（洪峰单独分桶） ----
    md.append("## 3. 分相延迟分桶（洪峰相位独立统计）\n\n");
    RoundResult last = c.rounds().get(c.rounds().size() - 1);
    md.append("| 相位 | 时长s | 请求数 | 平均QPS | P50 | P99 | max |\n|---|---|---|---|---|---|---|\n");
    for (String phase : c.curve().phaseNames()) {
      PerSecondRecorder.Stats s = last.byPhase().get(phase);
      double dur = c.curve().phaseDurationSec(phase);
      if (s == null || dur <= 0) {
        continue;
      }
      md.append(String.format("| %s | %.0f | %d | %.0f | %.2f | %.2f | %.2f |%n",
          phase, dur, s.count(), s.count() / dur, s.p(0.50), s.p(0.99), s.maxMs()));
    }
    md.append("\n");

    // ---- 4. 裁判断言 ----
    long totalTimeouts = c.rounds().stream().mapToLong(r -> r.judge().timeouts()).sum();
    long totalHttpErr = c.rounds().stream().mapToLong(r -> r.judge().httpErrors()).sum();
    long totalParseErr = c.rounds().stream().mapToLong(r -> r.judge().parseErrors()).sum();
    long totalFieldErr = c.rounds().stream().mapToLong(r -> r.judge().fieldErrors()).sum();
    long blackChecks = c.rounds().stream().mapToLong(r -> r.judge().blacklistChecks()).sum();
    long blackCorrect = c.rounds().stream().mapToLong(r -> r.judge().blacklistRefusalsCorrect()).sum();
    md.append("## 4. 裁判断言（判罚表）\n\n");
    md.append("| 断言 | 计数 | 判定 |\n|---|---|---|\n");
    md.append(String.format("| 黑名单用户参竞（违规） | %d | %s |%n", totalViolations,
        c.faultExpected()
            ? (totalViolations > 0 ? "✅ 断言生效实证（fault 演示预期）" : "❌ 未检出——断言失效")
            : (totalViolations == 0 ? "✅ 零违规" : "❌ 违规！")));
    md.append(String.format("| 黑名单正确拒绝 | %d / 检查 %d | %s |%n", blackCorrect, blackChecks,
        blackChecks > 0 && blackCorrect == blackChecks ? "✅" : "—"));
    md.append(String.format("| 响应 >60ms（超时） | %d（率 %.4f%%，红线 1%%） | %s |%n",
        totalTimeouts, last.judge().timeoutRate() * 100, timeoutPass ? "✅" : "❌"));
    md.append(String.format("| 字段缺失/非法 | %d | %s |%n", totalFieldErr, totalFieldErr == 0 ? "✅" : "❌"));
    md.append(String.format("| HTTP 错误 / 解析错误 | %d / %d | %s |%n", totalHttpErr, totalParseErr,
        (totalHttpErr + totalParseErr) == 0 ? "✅" : "❌"));
    if (totalViolations > 0) {
      md.append("\n违规样本（前 5 条，DoD k07 证据）：\n\n```\n");
      c.rounds().get(c.rounds().size() - 1).judge().samples().stream().limit(5)
          .forEach(s -> md.append(s).append("\n"));
      md.append("```\n");
    }
    md.append("\n");

    // ---- 5. 漏斗勾稽 ----
    md.append("## 5. 漏斗勾稽（样本量感知容差 = max(±3% 相对, 3σ 标准误)）\n\n");
    md.append("| 层 | 观测 | 配置 | 偏差 | 容差 | 样本 | 判定 |\n|---|---|---|---|---|---|---|\n");
    appendFunnelRow(md, "winRate", obsWin, c.funnelCfg().winRate(), c.participateCount(), winPass);
    appendFunnelRow(md, "ctr", obsCtr, c.funnelCfg().ctr(), c.funnel().exposures(), ctrPass);
    appendFunnelRow(md, "cvr", obsCvr, c.funnelCfg().cvr(), c.funnel().clicks(), cvrPass);
    md.append(String.format("\n计数：曝光 %d ≥ 点击 %d ≥ 转化 %d → 不变式 %s；参竞总数 %d%n",
        c.funnel().exposures(), c.funnel().clicks(), c.funnel().converts(),
        funnelInvariant ? "✅" : "❌", c.participateCount()));
    md.append("\n");

    // ---- 6. 流量分布 ----
    md.append("## 6. 流量分布（幂律验证）\n\n");
    if (distMeasurable) {
      md.append(String.format("- Top20%% 用户流量占比：实测 **%.3f**（理论 %.3f）→ %s（通过窗口 [0.75, 0.85]）%n",
          c.distribution().measuredTop20Share(), c.sampler().expectedTop20Share(),
          distPass ? "✅" : "❌"));
    } else {
      md.append(String.format(
          "- Top20%% 用户流量占比：**n/a（样本不足）**——distinct 用户 %d 未达池 20%%（%d），该口径需 acceptance 级请求量才可判读%n",
          c.distribution().observedUsers(),
          (int) Math.ceil(c.app().userPool().size() * 0.2)));
    }
    md.append(String.format("- 伪造 ID 占比：实测 %.4f（配置 %.2f）——M1 防穿透靶子%n",
        c.distribution().measuredFakeRatio(), c.app().userPool().fakeRatio()));
    md.append(String.format("- 真实池用户请求总数：%d%n%n", c.distribution().realTotal()));

    // ---- 7. requestId 贯穿示例 ----
    md.append("## 7. requestId 贯穿示例（DoD k03：五类记录同一 ID 串联）\n\n");
    if (c.traceSample() != null) {
      md.append("```json\n").append(c.traceSample()).append("\n```\n\n");
      md.append("> 同一 requestId 串联：request → response → EXPOSURE → CLICK → CONVERT；")
          .append("其 CLICK.cost 已计入账单（归属 ").append(c.bills().isEmpty() ? "-" : c.bills().get(0).date())
          .append(" / ").append(c.bills().isEmpty() ? "-" : c.bills().get(0).campaignId()).append("）。\n\n");
    } else {
      md.append("⚠️ 本次运行无转化链样本（traceSample 为空）\n\n");
    }

    // ---- 8. 账单 ----
    md.append("## 8. 模拟账单（T+1，协议 03）\n\n");
    md.append("| 日期 | 计划 | totalCost | impressions | clicks |\n|---|---|---|---|---|\n");
    for (BillRecord b : c.bills()) {
      md.append(String.format("| %s | %s | %.2f | %d | %d |%n",
          b.date(), b.campaignId(), b.totalCost(), b.impressions(), b.clicks()));
    }
    md.append(String.format("%n勾稽：clicks ≤ impressions → %s；文件：%s%n%n",
        billPass ? "✅" : "❌", c.billFile()));

    // ---- 9. 产出文件 ----
    md.append("## 9. 产出文件\n\n");
    md.append("- 本报告：`").append(runDir.resolve("report.md")).append("`\n");
    md.append("- 每轮秒级指标：`").append(runDir.resolve("round-N/metrics.jsonl")).append("`\n");
    md.append("- 贯穿链样本：`").append(runDir.resolve("traces.jsonl")).append("`\n");
    md.append("- 账单：`").append(c.billFile()).append("`\n");
    if (c.baselineFile() != null) {
      md.append("- 延迟基线（中位轮，预热剔除）：`").append(c.baselineFile()).append("`\n");
    }
    md.append("\n");

    // ---- 10. DoD 对照 ----
    md.append("## 10. M0 DoD 对照（本运行覆盖项）\n\n");
    md.append("| # | 项 | 判定 |\n|---|---|---|\n");
    md.append(doDRow("k03", "requestId 五类记录贯穿可查", tracePass));
    md.append(doDRow("k05", "QPS 稳定达标（目标 " + String.format("%.0f", targetAvgQps) + "）", qpsPass));
    md.append(doDRow("k06", "Top20% 用户 ≈80% 流量（幂律）", distMeasurable ? distPass : null));
    md.append(doDRow("k07", "断言生效（fault 模式违规检出）", violationPass));
    md.append(doDRow("k08", "漏斗勾稽 ±3%", winPass && ctrPass && cvrPass && funnelInvariant));
    md.append(doDRow("k09", "账单生成且勾稽自洽", billPass));
    md.append(doDRow("k10", "延迟基线落盘（仅 acceptance 适用）",
        "acceptance".equals(c.profileName()) ? c.baselineFile() != null : null));
    md.append(doDRow("—", "err 率 ≤1% 且超时率 ≤1%（红线）", errPass && timeoutPass));
    md.append("\n");

    boolean pass = qpsPass && timeoutPass && errPass && violationPass && winPass && ctrPass
        && cvrPass && funnelInvariant && distPass && billPass && tracePass;

    md.append("## 总判定：").append(pass ? "**PASS** ✅" : "**FAIL** ❌").append("\n");
    if (!pass) {
      md.append("\n未过项：");
      if (!qpsPass)
        md.append(" QPS;");
      if (!timeoutPass)
        md.append(" 超时率;");
      if (!errPass)
        md.append(" err率;");
      if (!violationPass)
        md.append(" 黑名单断言;");
      if (!winPass || !ctrPass || !cvrPass || !funnelInvariant)
        md.append(" 漏斗勾稽;");
      if (!distPass)
        md.append(" 幂律分布;");
      if (!billPass)
        md.append(" 账单;");
      if (!tracePass)
        md.append(" 贯穿链;");
      md.append("\n");
    }

    Files.writeString(runDir.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);
    return pass;
  }

  /** pass=null 表示本运行不适用/不可判读（低流量 profile 的 k06、非 acceptance 的 k10） */
  private static String doDRow(String id, String item, Boolean pass) {
    String verdict = pass == null ? "— n/a" : (pass ? "✅" : "❌");
    return "| " + id + " | " + item + " | " + verdict + " |\n";
  }

  private static String curveDesc(TrafficConfig.Profile p) {
    StringBuilder sb = new StringBuilder();
    for (TrafficConfig.Phase ph : p.phases()) {
      if (!sb.isEmpty()) {
        sb.append(" → ");
      }
      sb.append(ph.qps()).append("qps×").append(ph.sec()).append("s");
    }
    return sb.toString();
  }

  /** 漏斗勾稽表行：容差与样本量一起展示，判定依据透明 */
  private static void appendFunnelRow(StringBuilder md, String layer, double obs, double cfg,
      long n, boolean pass) {
    double tol = Math.max(cfg * 0.03, 3 * Math.sqrt(cfg * (1 - cfg) / Math.max(1, n)));
    md.append(String.format("| %s | %.4f | %.2f | %+.2f%% | ±%.2f%% | %d | %s |%n",
        layer, obs, cfg, relDiff(obs, cfg) * 100, tol / cfg * 100, n, pass ? "✅" : "❌"));
  }

  private static boolean withinRel(double obs, double cfg, double tol) {
    return Math.abs(obs - cfg) <= cfg * tol;
  }

  /**
   * 样本量感知勾稽容差：tol = max(相对容差, 3×二项标准误)。
   * 固定 ±3% 相对窗口只对大样本层有效（win/ctr 层 ~60 万样本有 ~12σ 余量）；
   * cvr 层点击样本仅万级，1σ ≈ 相对 5%，固定 3% 窗口必然把正常涨落误判为失败。
   * 真正的漏斗逻辑错误（概率接错层级）偏差通常 >10%，3σ 容差依然能抓住。
   */
  private static boolean withinRelSamples(double obs, double cfg, double relTol, long n) {
    double tol = Math.max(cfg * relTol, 3 * Math.sqrt(cfg * (1 - cfg) / Math.max(1, n)));
    return Math.abs(obs - cfg) <= tol;
  }

  private static double relDiff(double obs, double cfg) {
    return cfg == 0 ? 0 : (obs - cfg) / cfg;
  }
}
