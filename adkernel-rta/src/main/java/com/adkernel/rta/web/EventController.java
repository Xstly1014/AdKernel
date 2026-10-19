package com.adkernel.rta.web;

import com.adkernel.common.protocol.CallbackEvent;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.util.Json;
import com.adkernel.rta.core.EventSink;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 转化回传接口：POST /api/event（媒体 → AdKernel，漏斗逐级上报）。
 * 事件全量异步落盘（主链路零阻塞），M4 起在本入口接 mini-MQ 管道。
 */
@RestController
public class EventController {

    private final EventSink eventSink;

    public EventController(EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @PostMapping(value = "/api/event", consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> accept(@RequestBody(required = false) String body) {
        CallbackEvent event = body == null ? null : Json.read(body, CallbackEvent.class);
        if (event == null
                || isBlank(event.requestId()) || isBlank(event.campaignId())
                || event.eventType() == null || event.eventTime() <= 0
                || isBlank(event.expId())) {
            return ResponseEntity.badRequest().body(Map.of("code", RtaResponse.CODE_BAD_FIELD, "message", "missing_or_invalid_field"));
        }
        eventSink.accept(event);
        return ResponseEntity.ok(Map.of("code", 0));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
