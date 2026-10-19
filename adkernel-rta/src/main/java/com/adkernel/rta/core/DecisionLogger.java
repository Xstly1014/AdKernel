package com.adkernel.rta.core;

import com.adkernel.common.protocol.RtaRequest;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.util.Json;
import com.adkernel.rta.config.RtaProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 决策自记（抽样落盘）：黑名单拒绝 100% 记录 + 正常决策按比例抽样。
 * 诊断用途（M3 对账的正式自记源是事件流，非决策流）。
 */
@Component
public class DecisionLogger {

    private static final Logger log = LoggerFactory.getLogger(DecisionLogger.class);

    private final JsonlAppender appender;
    private final double sampleRate;

    public DecisionLogger(RtaProperties props) {
        try {
            Path dir = Path.of(props.dataDir());
            Files.createDirectories(dir);
            this.appender = new JsonlAppender(dir.resolve("rta-decisions.jsonl"));
        } catch (Exception e) {
            throw new IllegalStateException("cannot create decision log dir", e);
        }
        this.sampleRate = props.decisionSampleRate();
        log.info("decision logger ready: sampleRate={}, refusals=100%", sampleRate);
    }

    public void log(RtaRequest request, RtaResponse response) {
        // 黑名单拒绝全量记录（低频 + k07 证据）；正常决策抽样（控制磁盘体积）
        if (response.target() && ThreadLocalRandom.current().nextDouble() >= sampleRate) {
            return;
        }
        appender.append(Json.write(Map.of(
                "ts", System.currentTimeMillis(),
                "request", request,
                "response", response)));
    }

    @PreDestroy
    public void close() {
        appender.close();
    }
}
