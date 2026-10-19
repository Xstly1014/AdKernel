package com.adkernel.rta.core;

import com.adkernel.common.protocol.RtaRequest;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.user.UserPool;
import com.adkernel.rta.config.RtaProperties;
import org.springframework.stereotype.Service;

/**
 * M0 echo 决策：恒参竞（target=true / bidRatio=1.0），仅保留静态黑名单拒绝。
 *
 * <p>M2 起本类被策略引擎（规则 DSL + 频控 + 价值分层 + 原子热更新）替换，
 * 接口签名不变——echo 是决策编排链路的第一块可插拔零件。</p>
 */
@Service
public class EchoDecisionService {

    private final UserPool userPool;
    private final RtaProperties props;

    public EchoDecisionService(UserPool userPool, RtaProperties props) {
        this.userPool = userPool;
        this.props = props;
    }

    public RtaResponse decide(RtaRequest req) {
        // 故障注入开关（k07 实证用）：开启后跳过黑名单检查，裁判必然记违规
        if (!props.faultBlacklistBypass() && userPool.isBlacklisted(req.userId())) {
            return RtaResponse.ok(false, 0, props.expId(), "blacklist");
        }
        return RtaResponse.ok(true, 1.0, props.expId(), "echo");
    }
}
