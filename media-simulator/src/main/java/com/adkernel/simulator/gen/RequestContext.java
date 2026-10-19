package com.adkernel.simulator.gen;

import com.adkernel.common.protocol.RtaRequest;

/** 一次决策请求的完整上下文（生成侧标记，供裁判与分布统计使用） */
public record RequestContext(
        RtaRequest request,
        boolean fakeUser,   // 伪造 ID（与真实 ID 同格式，服务端不可区分；M1 防穿透靶子）
        boolean blacklisted, // 黑名单真值（裁判判罚依据）
        int poolIndex       // 池内下标（幂律分布统计；伪造 ID 为 -1）
) {
}
