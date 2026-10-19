package com.adkernel.simulator.gen;

import com.adkernel.common.auth.HmacSigner;
import com.adkernel.common.protocol.RtaRequest;
import com.adkernel.common.user.UserPool;
import com.adkernel.simulator.config.AppConfig;
import com.adkernel.simulator.traffic.ZipfSampler;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 请求生成器：Zipf 用户抽取 + 伪造 ID 混入 + 场景分布 + 签名（媒体侧身份） */
public final class RequestGenerator {

    private final UserPool pool;
    private final ZipfSampler sampler;
    private final AppConfig cfg;

    public RequestGenerator(UserPool pool, ZipfSampler sampler, AppConfig cfg) {
        this.pool = pool;
        this.sampler = sampler;
        this.cfg = cfg;
    }

    public RequestContext next() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boolean fake = rnd.nextDouble() < cfg.userPool().fakeRatio();
        int idx = -1;
        String userId;
        if (fake) {
            userId = UserPool.fakeUserId();
        } else {
            idx = sampler.sample();
            userId = pool.userId(idx);
        }
        boolean black = !fake && pool.isBlacklisted(userId);
        boolean feed = rnd.nextDouble() < cfg.scenes().feed();
        String scene = feed ? "feed" : "splash";
        String slot = feed ? "slot-feed-1001" : "slot-splash-2001";
        String requestId = "req-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        String sign = HmacSigner.sign(requestId, now, userId, cfg.auth().secretKey());
        return new RequestContext(
                new RtaRequest(requestId, now, "cmp-001", userId, slot, scene, sign),
                fake, black, idx);
    }
}
