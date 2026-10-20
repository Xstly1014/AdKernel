package com.adkernel.common.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ThreadLocalRandom;

/**
 * userId 生成规则（唯一真源）：模拟器、RTA、特征服务三方共享。
 *
 * <ul>
 * <li>真实 ID：{@code "u-" + hex(sha256("adkernel|user|" + i)).substring(0,12)}，形如脱敏哈希 ID；</li>
 * <li>伪造 ID：与真实 ID 同格式（服务端不可区分），由生成方自行标记。</li>
 * </ul>
 */
public final class UserIds {

    private UserIds() {
    }

    /** 池内第 i 个用户的确定性 ID */
    public static String userId(int i) {
        return "u-" + sha256Hex12("adkernel|user|" + i);
    }

    /** 生成一个与真实 ID 同格式的伪造用户 ID */
    public static String fakeUserId() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        return String.format("u-%012x", rnd.nextLong() & 0xFFFFFFFFFFFFL);
    }

    private static String sha256Hex12(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }
}
