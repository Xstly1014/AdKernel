package com.adkernel.common.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Jackson 静态封装：全局复用单例 ObjectMapper（避免热循环重复创建），未知字段容忍（前向兼容）。 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    public static <T> T read(String content, Class<T> type) {
        try {
            return MAPPER.readValue(content, type);
        } catch (Exception e) {
            return null; // 解析失败统一返回 null，由调用方记协议错误（裁判口径）
        }
    }
}
