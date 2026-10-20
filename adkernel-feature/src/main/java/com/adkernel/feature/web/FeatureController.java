package com.adkernel.feature.web;

import com.adkernel.feature.cache.L2Cache;
import com.adkernel.feature.core.FeatureService;
import com.adkernel.feature.source.ProfileSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 特征服务接口（协议 04）：
 * <ul>
 * <li>GET  /internal/feature/{userId}——特征查询（exist + layer + profile）；</li>
 * <li>POST /internal/admin/inject——延迟/hang 注入开关（k09 实验要件）；</li>
 * <li>GET  /internal/admin/stats——命中率/拦截数/源查询统计（验收断言数据源）；</li>
 * <li>POST /internal/admin/experiment/ttl-spread——TTL 偏移分布验证（k06，不污染缓存）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal")
public class FeatureController {

    private final FeatureService service;
    private final ProfileSource source;
    private final L2Cache l2;
    private volatile long l2HangUntil = 0;

    public FeatureController(FeatureService service, ProfileSource source, L2Cache l2) {
        this.service = service;
        this.source = source;
        this.l2 = l2;
    }

    public record FeatureResponse(boolean exist, String layer, Map<String, Object> profile) {
    }

    @GetMapping("/feature/{userId}")
    public FeatureResponse feature(@PathVariable String userId) {
        FeatureService.FeatureResult r = service.get(userId);
        Map<String, Object> p = r.profile() == null ? null : toMap(r.profile());
        return new FeatureResponse(r.exist(), r.layer(), p);
    }

    public record InjectRequest(long sourceLatencyMs, long l2HangMs, Boolean bypassLayers) {
    }

    @PostMapping("/admin/inject")
    public Map<String, Object> inject(@RequestBody InjectRequest req) {
        source.setInjectLatencyMs(Math.max(0, req.sourceLatencyMs()));
        if (req.l2HangMs() > 0) {
            l2.hangFor(req.l2HangMs());
            l2HangUntil = System.currentTimeMillis() + req.l2HangMs();
        } else {
            l2HangUntil = 0;
        }
        if (req.bypassLayers() != null) {
            service.setBypassLayers(req.bypassLayers());
        }
        return Map.of(
                "sourceLatencyMs", source.injectLatencyMs(),
                "l2HangMs", req.l2HangMs(),
                "bypassLayers", service.bypassLayers());
    }

    @GetMapping("/admin/stats")
    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>(service.stats());
        s.put("inject", Map.of("sourceLatencyMs", source.injectLatencyMs(), "l2HangUntil", l2HangUntil));
        return s;
    }

    public record TtlSpreadRequest(int n) {
    }

    /** k06：抽样 n 次 TTL 抽签，返回秒桶直方图（批量写入后不集中过期的证据） */
    @PostMapping("/admin/experiment/ttl-spread")
    public Map<String, Object> ttlSpread(@RequestBody TtlSpreadRequest req) {
        int n = Math.min(Math.max(1, req.n()), 100_000);
        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            buckets.merge(l2.ttlWithJitter() / 1000, 1, Integer::sum);
        }
        long maxBucket = buckets.values().stream().mapToLong(Integer::longValue).max().orElse(0);
        return Map.of(
                "samples", n,
                "distinctSeconds", buckets.size(),
                "maxBucketCount", maxBucket,
                "maxBucketShare", n == 0 ? 0 : (double) maxBucket / n,
                "buckets", buckets);
    }

    private static Map<String, Object> toMap(com.adkernel.common.user.UserProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", p.userId());
        m.put("memberLevel", p.memberLevel().name());
        m.put("blacklisted", p.blacklisted());
        m.put("lastConvertTime", p.lastConvertTime());
        m.put("impressionCount7d", p.impressionCount7d());
        m.put("firstSeenTime", p.firstSeenTime());
        return m;
    }
}
