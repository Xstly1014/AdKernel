package com.adkernel.simulator.trace;

import com.adkernel.common.protocol.CallbackEvent;
import com.adkernel.common.protocol.RtaRequest;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.common.util.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * requestId 贯穿链记录器：每次转化落一条完整五段链
 * （request → response → exposure → click → convert，同一 requestId 串联）——DoD k03 的直接证据。
 * 转化事件量级低（万级 QPS 下 ~3.6/s），同步写 + flush 即可。
 */
public final class TraceRecorder implements AutoCloseable {

    private final Path path;
    private final BufferedWriter writer;

    public TraceRecorder(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void record(RtaRequest request, RtaResponse response, CallbackEvent... events) {
        try {
            writer.write(Json.write(Map.of(
                    "ts", System.currentTimeMillis(),
                    "request", request,
                    "response", response,
                    "events", List.of(events))));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("trace write failed", e);
        }
    }

    /** 首条贯穿链（报告示例用） */
    public synchronized String firstTraceLine() {
        try {
            return Files.exists(path) ? Files.readString(path).split("\n", 2)[0] : null;
        } catch (IOException e) {
            return null;
        }
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void close() throws IOException {
        writer.flush();
        writer.close();
    }
}
