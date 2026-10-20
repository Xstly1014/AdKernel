package com.adkernel.rta.web;

import com.adkernel.rta.feature.FeatureCacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RTA 侧 L1 观测与实验开关（协议 04 §4）：
 * <ul>
 * <li>GET  /internal/stats——L1 命中率 / 降级分层计数 / 特征查询延迟分布（k04/k05/k10 断言数据源）；</li>
 * <li>POST /internal/admin/cache——bypass 开关（k10：12ms 对照基线）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal")
public class FeatureStatsController {

    private final FeatureCacheService cache;

    public FeatureStatsController(FeatureCacheService cache) {
        this.cache = cache;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("l1", cache.stats());
    }

    public record BypassRequest(boolean bypass) {
    }

    @PostMapping("/admin/cache")
    public Map<String, Object> setBypass(@RequestBody BypassRequest req) {
        cache.setBypass(req.bypass());
        return Map.of("cacheBypass", cache.bypass());
    }
}
