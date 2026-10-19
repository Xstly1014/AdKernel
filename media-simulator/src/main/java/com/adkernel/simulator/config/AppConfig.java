package com.adkernel.simulator.config;

import java.util.Map;
import java.util.List;

/** 模拟器主配置（config/simulator.yaml） */
public record AppConfig(
        Endpoints endpoints,
        UserPoolCfg userPool,
        Scenes scenes,
        AuthCfg auth,
        int clientTimeoutMs,   // 媒体侧超时预算（红线 60ms：超时按媒体默认策略处理=不参竞）
        String dataDir         // 运行产出根目录（相对 CWD）
) {
    public record Endpoints(String decisionUrl, String eventUrl) {}

    /** 用户池：规模 + Zipf 幂律指数 + 伪造 ID 比例（M1 防穿透靶子） */
    public record UserPoolCfg(int size, double zipfS, double fakeRatio) {}

    /** 场景分布：feed 信息流 / splash 开屏（M2 场景化策略输入） */
    public record Scenes(double feed, double splash) {}

    public record AuthCfg(String secretKey) {}
}
