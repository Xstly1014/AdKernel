package com.adkernel.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HmacSha256 签名（对齐华为鲸鸿动能 RTA 接口鉴权）。
 *
 * <pre>sign = hex_lower( HmacSha256( requestId + requestTime + userId, secretKey ) )</pre>
 *
 * <p>设计要点：
 * 签名覆盖请求体三个关键字段——防篡改（改任何一字段则签名失配）；
 * requestTime 参与签名 + 独立新鲜度窗口（TTL）——防重放。
 * Mac 实例非线程安全，每次调用新建（M0 口径；若 M5 火焰图显示为热点再优化为对象池）。</p>
 */
public final class HmacSigner {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HmacSigner() {
    }

    /** 计算签名 */
    public static String sign(String requestId, long requestTime, String userId, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String data = requestId + requestTime + userId;
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return toHexLower(digest);
        } catch (Exception e) {
            throw new IllegalStateException("hmac-sha256 unavailable", e);
        }
    }

    /** 验签（常数时间比较，防时序侧信道） */
    public static boolean verify(String requestId, long requestTime, String userId,
                                 String secretKey, String expectedSign) {
        if (expectedSign == null || expectedSign.isEmpty()) {
            return false;
        }
        String actual = sign(requestId, requestTime, userId, secretKey);
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expectedSign.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHexLower(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
