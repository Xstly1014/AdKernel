package com.adkernel.rta.core;

import com.adkernel.common.protocol.CallbackEvent;
import com.adkernel.common.util.Json;
import com.adkernel.rta.config.RtaProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 回传事件自记：AdKernel 侧收到的曝光/点击/转化事件全量异步落盘。
 * 这是 M3 对账"自记消耗"的源头（自记消耗 = 本文件 CLICK 事件 cost 聚合）。
 */
@Component
public class EventSink {

    private static final Logger log = LoggerFactory.getLogger(EventSink.class);

    private final JsonlAppender appender;

    public EventSink(RtaProperties props) {
        try {
            Path dir = Path.of(props.dataDir());
            Files.createDirectories(dir);
            this.appender = new JsonlAppender(dir.resolve("rta-events.jsonl"));
        } catch (Exception e) {
            throw new IllegalStateException("cannot create event log dir", e);
        }
        log.info("event sink ready: {}", appender.path());
    }

    public void accept(CallbackEvent event) {
        appender.append(Json.write(event));
    }

    /** 供对账预演（recon）读取的文件路径 */
    public Path eventsPath() {
        return appender.path();
    }

    @PreDestroy
    public void close() {
        appender.close();
    }
}
