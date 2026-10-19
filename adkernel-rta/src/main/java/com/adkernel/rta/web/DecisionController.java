package com.adkernel.rta.web;

import com.adkernel.common.auth.HmacSigner;
import com.adkernel.common.protocol.RtaRequest;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.util.Json;
import com.adkernel.rta.config.RtaProperties;
import com.adkernel.rta.core.DecisionLogger;
import com.adkernel.rta.core.EchoDecisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 决策接口：POST /api/rta/decision（媒体 → AdKernel）。
 *
 * <p>校验链（顺序即语义）：JSON 解析 → 字段校验 → 新鲜度（TTL 防重放）→ 验签（防篡改）→ 决策。
 * 延迟预算口径：接入层 ~10ms（协议 01 / Spec 02），全程软超时——宁可保守决策，绝不超时。</p>
 */
@RestController
public class DecisionController {

    private static final Logger log = LoggerFactory.getLogger(DecisionController.class);

    private final RtaProperties props;
    private final EchoDecisionService decisionService;
    private final DecisionLogger decisionLogger;

    public DecisionController(RtaProperties props, EchoDecisionService decisionService,
                              DecisionLogger decisionLogger) {
        this.props = props;
        this.decisionService = decisionService;
        this.decisionLogger = decisionLogger;
    }

    @PostMapping(value = "/api/rta/decision", consumes = "application/json", produces = "application/json")
    public ResponseEntity<RtaResponse> decide(@RequestBody(required = false) String body) {
        // ① JSON 解析
        RtaRequest req = body == null ? null : Json.read(body, RtaRequest.class);
        if (req == null) {
            return ResponseEntity.badRequest().body(RtaResponse.error(RtaResponse.CODE_BAD_FIELD, "malformed_json"));
        }
        // ② 字段校验（必填非空 + 时间戳合法）
        if (isBlank(req.requestId()) || isBlank(req.campaignId()) || isBlank(req.userId())
                || isBlank(req.adSlotId()) || isBlank(req.scene()) || isBlank(req.sign())
                || req.requestTime() <= 0) {
            return ResponseEntity.badRequest().body(RtaResponse.error(RtaResponse.CODE_BAD_FIELD, "missing_or_invalid_field"));
        }
        // ③ 新鲜度：防重放（requestTime 参与签名，过期签名直接拒）
        long now = System.currentTimeMillis();
        if (Math.abs(now - req.requestTime()) > props.signTtlMs()) {
            return ResponseEntity.status(401).body(RtaResponse.error(RtaResponse.CODE_STALE_TIME, "stale_request_time"));
        }
        // ④ 验签：防篡改（常数时间比较）
        if (!HmacSigner.verify(req.requestId(), req.requestTime(), req.userId(),
                props.secretKey(), req.sign())) {
            return ResponseEntity.status(401).body(RtaResponse.error(RtaResponse.CODE_BAD_SIGN, "invalid_signature"));
        }
        // ⑤ 决策（echo：恒参竞 + 静态黑名单）
        RtaResponse resp = decisionService.decide(req);
        decisionLogger.log(req, resp);
        return ResponseEntity.ok(resp);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
