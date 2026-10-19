package com.adkernel.simulator.funnel;

import com.adkernel.common.protocol.CallbackEvent;
import com.adkernel.common.protocol.EventType;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.simulator.config.AppConfig;
import com.adkernel.simulator.config.FunnelConfig;
import com.adkernel.simulator.gen.RequestContext;
import com.adkernel.simulator.http.RtaClient;
import com.adkernel.simulator.trace.TraceRecorder;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * 转化漏斗引擎（媒体侧）：参竞 → winRate 胜出 → 曝光 → ctr 点击 → cvr 转化。
 *
 * <p>
 * requestId 闭包纪律：回传事件在构造时捕获决策请求的 requestId / campaignId / expId，
 * 禁止重新生成——异步上下文丢失关联是归因断链的最大隐患（协议 02 §6）。
 * </p>
 */
public final class FunnelEngine {

  private final RtaClient client;
  private final AppConfig cfg;
  private final FunnelConfig funnel;
  private final TraceRecorder traceRecorder;

  private final LongAdder exposures = new LongAdder();
  private final LongAdder clicks = new LongAdder();
  private final LongAdder converts = new LongAdder();

  /** 账单累计（计划维度，整运行聚合；去重口径见协议 03） */
  private final ConcurrentHashMap<String, BillAcc> billByCampaign = new ConcurrentHashMap<>();
  private final Set<String> billDedup = ConcurrentHashMap.newKeySet();

  /** 账单累计器（billing 包生成账单时跨包读取，字段公开） */
  public static final class BillAcc {
    public final LongAdder impressions = new LongAdder();
    public final LongAdder clicks = new LongAdder();
    public final DoubleAdder cost = new DoubleAdder();
  }

  public FunnelEngine(RtaClient client, AppConfig cfg, FunnelConfig funnel, TraceRecorder traceRecorder) {
    this.client = client;
    this.cfg = cfg;
    this.funnel = funnel;
    this.traceRecorder = traceRecorder;
  }

  /** 决策响应 → 按漏斗概率逐级上报（M0 同步发，M4 起引入真实时延） */
  public void onDecision(RequestContext ctx, RtaResponse resp) {
    if (resp == null || !resp.target()) {
      return; // 未参竞 → 无漏斗
    }
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    if (rnd.nextDouble() >= funnel.winRate()) {
      return; // 竞价未胜出 → 无事件
    }
    // 闭包捕获：回传事件与决策请求同 requestId / campaignId / expId
    String rid = ctx.request().requestId();
    String cid = ctx.request().campaignId();
    String expId = resp.expId();
    long now = System.currentTimeMillis();

    CallbackEvent exposure = new CallbackEvent(rid, EventType.EXPOSURE, now, cid, expId, 0);
    client.postJson(cfg.endpoints().eventUrl(), exposure);
    exposures.increment();
    billEvent(exposure);

    if (rnd.nextDouble() < funnel.ctr()) {
      double cost = Math.round((funnel.cpcMin()
          + rnd.nextDouble() * (funnel.cpcMax() - funnel.cpcMin())) * 100.0) / 100.0;
      CallbackEvent click = new CallbackEvent(rid, EventType.CLICK, now, cid, expId, cost);
      client.postJson(cfg.endpoints().eventUrl(), click);
      clicks.increment();
      billEvent(click);

      if (rnd.nextDouble() < funnel.cvr()) {
        CallbackEvent convert = new CallbackEvent(rid, EventType.CONVERT, now, cid, expId, 0);
        client.postJson(cfg.endpoints().eventUrl(), convert);
        converts.increment();
        billEvent(convert);
        // 五类记录贯穿链：request → response → exposure → click → convert（账单为聚合归属）
        traceRecorder.record(ctx.request(), resp, exposure, click, convert);
      }
    }
  }

  /** 入账（协议 03 去重口径：requestId+eventType 只计一次；CPC 只累计 CLICK.cost） */
  private void billEvent(CallbackEvent e) {
    if (!billDedup.add(e.requestId() + "|" + e.eventType())) {
      return;
    }
    BillAcc acc = billByCampaign.computeIfAbsent(e.campaignId(), k -> new BillAcc());
    switch (e.eventType()) {
      case EXPOSURE -> acc.impressions.increment();
      case CLICK -> {
        acc.clicks.increment();
        acc.cost.add(e.cost());
      }
      case CONVERT -> {
        /* 转化不计费（CPC 口径） */ }
    }
  }

  public long exposures() {
    return exposures.sum();
  }

  public long clicks() {
    return clicks.sum();
  }

  public long converts() {
    return converts.sum();
  }

  public ConcurrentHashMap<String, BillAcc> billByCampaign() {
    return billByCampaign;
  }

  /** 漏斗观测比率（勾稽校验：与配置 ±3% 相对） */
  public double observedWinRate(long participateCount) {
    return participateCount == 0 ? 0 : (double) exposures.sum() / participateCount;
  }

  public double observedCtr() {
    return exposures.sum() == 0 ? 0 : (double) clicks.sum() / exposures.sum();
  }

  public double observedCvr() {
    return clicks.sum() == 0 ? 0 : (double) converts.sum() / clicks.sum();
  }
}
