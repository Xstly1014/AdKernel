package com.adkernel.simulator.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/** YAML 配置加载（三份：simulator / traffic / funnel） */
public final class ConfigLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ConfigLoader() {
    }

    public static AppConfig loadApp(Path configDir) {
        return read(configDir.resolve("simulator.yaml"), AppConfig.class);
    }

    public static TrafficConfig loadTraffic(Path configDir) {
        return read(configDir.resolve("traffic.yaml"), TrafficConfig.class);
    }

    public static FunnelConfig loadFunnel(Path configDir) {
        return read(configDir.resolve("funnel.yaml"), FunnelConfig.class);
    }

    private static <T> T read(Path file, Class<T> type) {
        try {
            if (!Files.exists(file)) {
                throw new IllegalStateException("config file not found: " + file.toAbsolutePath());
            }
            return YAML.readValue(Files.readString(file), type);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load config: " + file, e);
        }
    }
}
