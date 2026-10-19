package com.adkernel.simulator.judge;

import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.simulator.gen.RequestContext;
import com.adkernel.simulator.http.ApiResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * 裁判（ADR-0001 三合一角色的核心）：对每次决策响应执行业务合法性断言。
 * 判罚表见 docs（M0 手册速览卡）：黑名单参竞=违规；>60ms=超时；超时率>1%=红线标红；
 * 字段缺失/非法=错误；验签失败=错误。预算/频控断言为 M2/M3 预留。
 */
public final class Judge {

    private final LongAdder total = new LongAdder();
    private final LongAdder fakeRequests = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder httpErrors = new LongAdder();
    private final LongAdder parseErrors = new LongAdder();
    private final LongAdder fieldErrors = new LongAdder();
    private final LongAdder blacklistChecks = new LongAdder();
    private final LongAdder blacklistViolations = new LongAdder();
    private final LongAdder blacklistRefusalsCorrect = new LongAdder();
    private final Queue<ViolationSample> violationSamples = new ConcurrentLinkedQueue<>();

    private static final int MAX_SAMPLES = 20;

    /** 违规样本（报告证据，封顶 20 条） */
    public record ViolationSample(long ts, String requestId, String userId, String note) {}

    public void evaluate(RequestContext ctx, ApiResult r, RtaResponse parsed) {
        total.increment();
        if (ctx.fakeUser()) {
            fakeRequests.increment();
        }
        if (r.timedOut()) {
            timeouts.increment(); // 媒体按默认策略处理（不参竞）
            return;
        }
        if (r.ioError() || r.status() != 200) {
            httpErrors.increment();
            return;
        }
        if (parsed == null) {
            parseErrors.increment();
            return;
        }
        // 响应契约：code=0 时 expId 必须非空、bidRatio 必须在 [0,10]
        if (parsed.code() == 0
                && (parsed.expId() == null || parsed.expId().isBlank()
                || parsed.bidRatio() < 0 || parsed.bidRatio() > 10)) {
            fieldErrors.increment();
            return;
        }
        // 核心判罚：黑名单用户不得参竞（真值来自共享确定性用户池）
        if (ctx.blacklisted()) {
            blacklistChecks.increment();
            if (parsed.target()) {
                blacklistViolations.increment();
                if (violationSamples.size() < MAX_SAMPLES) {
                    violationSamples.offer(new ViolationSample(
                            System.currentTimeMillis(),
                            ctx.request().requestId(), ctx.request().userId(),
                            "blacklist_user_target_true"));
                }
            } else {
                blacklistRefusalsCorrect.increment();
            }
        }
    }

    public long total() { return total.sum(); }
    public long fakeRequests() { return fakeRequests.sum(); }
    public long timeouts() { return timeouts.sum(); }
    public long httpErrors() { return httpErrors.sum(); }
    public long parseErrors() { return parseErrors.sum(); }
    public long fieldErrors() { return fieldErrors.sum(); }
    public long blacklistChecks() { return blacklistChecks.sum(); }
    public long blacklistViolations() { return blacklistViolations.sum(); }
    public long blacklistRefusalsCorrect() { return blacklistRefusalsCorrect.sum(); }

    public double timeoutRate() {
        long t = total.sum();
        return t == 0 ? 0 : (double) timeouts.sum() / t;
    }

    public double errRate() {
        long t = total.sum();
        return t == 0 ? 0 : (double) (httpErrors.sum() + parseErrors.sum() + fieldErrors.sum()) / t;
    }

    public List<ViolationSample> samples() {
        return new ArrayList<>(violationSamples);
    }

    /** 超时率红线判定（1%：对齐腾讯 5% 清退红线留 5 倍安全边际） */
    public boolean timeoutRedLineExceeded() {
        return timeoutRate() > 0.01;
    }
}
