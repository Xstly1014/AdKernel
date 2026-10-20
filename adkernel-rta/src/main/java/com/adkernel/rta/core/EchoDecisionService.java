package com.adkernel.rta.core;

import com.adkernel.common.protocol.RtaRequest;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.user.UserProfile;
import com.adkernel.rta.config.RtaProperties;
import com.adkernel.rta.feature.FeatureCacheService;
import org.springframework.stereotype.Service;

/**
 * M1 echo 决策：恒参竞（target=true / bidRatio=1.0），黑名单与画像判断改查特征
 * （L1 Caffeine → 特征服务 → 降级链；M0 静态名单口径退役，裁判真值不变——特征源与
 * UserPool 同规则生成，逐用户一致）。
 *
 * <p>无画像/降级保守：target=false, reason=no_profile（合法长尾 + 伪造 ID + 降级兜底统一口径）。</p>
 *
 * <p>M2 起本类被策略引擎（规则 DSL + 频控 + 价值分层 + 原子热更新）替换，
 * 接口签名不变——echo 是决策编排链路的第一块可插拔零件。</p>
 */
@Service
public class EchoDecisionService {

    private final FeatureCacheService features;
    private final RtaProperties props;

    public EchoDecisionService(FeatureCacheService features, RtaProperties props) {
        this.features = features;
        this.props = props;
    }

    public RtaResponse decide(RtaRequest req) {
        UserProfile profile = features.get(req.userId());
        // 故障注入开关（k07 实证用）：开启后无视特征黑名单，裁判必然记违规
        if (profile == null) {
            return RtaResponse.ok(false, 0, props.expId(), "no_profile");
        }
        if (!props.faultBlacklistBypass() && profile.blacklisted()) {
            return RtaResponse.ok(false, 0, props.expId(), "blacklist");
        }
        return RtaResponse.ok(true, 1.0, props.expId(), "echo");
    }
}
