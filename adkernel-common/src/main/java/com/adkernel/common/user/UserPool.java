package com.adkernel.common.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 确定性用户池——模拟世界的"真值"（ground truth）。
 *
 * <p>
 * 模拟器（裁判）与 echo 服务共享同一套确定性生成规则（sha256 种子串），两侧零文件同步成本：
 * <ul>
 * <li>userId = "u-" + hex(sha256("adkernel|user|" + i)).substring(0, 12)，形如脱敏哈希
 * ID；</li>
 * <li>黑名单基准：i % BLACKLIST_MOD == 0（约 1% 用户）——M0 用静态名单口径（协议/域模型已声明），
 * M1 起 echo 侧改查特征库，裁判真值不变；</li>
 * <li>伪造 ID：与真实 ID 同格式（服务端不可区分），由生成方自行标记——M1 防穿透的靶子。</li>
 * </ul>
 * 生成 10 万用户启动耗时约 100ms，双方各自本地生成。
 * </p>
 */
public final class UserPool {

  public static final int DEFAULT_SIZE = 100_000;
  public static final int BLACKLIST_MOD = 100; // 1% 黑名单基准

  private final String[] userIds;
  private final Set<String> known;
  private final Set<String> blacklist;

  public UserPool(int size) {
    if (size <= 0) {
      throw new IllegalArgumentException("user pool size must be positive: " + size);
    }
    this.userIds = new String[size];
    this.known = new HashSet<>(size * 2);
    Set<String> black = new HashSet<>(size / BLACKLIST_MOD + 1);
    for (int i = 0; i < size; i++) {
      String id = "u-" + sha256Hex12("adkernel|user|" + i);
      userIds[i] = id;
      known.add(id);
      if (i % BLACKLIST_MOD == 0) {
        black.add(id);
      }
    }
    this.blacklist = black;
  }

  public int size() {
    return userIds.length;
  }

  public String userId(int index) {
    return userIds[index];
  }

  /** 是否为池内已知用户（用于分布统计口径：伪造 ID 不计入幂律检验）；M1 起该能力移交特征库 */
  public boolean isKnown(String userId) {
    return known.contains(userId);
  }

  /** 黑名单真值（裁判断言依据 + echo 静态名单） */
  public boolean isBlacklisted(String userId) {
    return blacklist.contains(userId);
  }

  public int blacklistSize() {
    return blacklist.size();
  }

  /** 生成一个与真实 ID 同格式的伪造用户 ID（服务端不可区分；生成方自行标记 isFake） */
  public static String fakeUserId() {
    return "u-" + randomHex12();
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

  private static String randomHex12() {
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    return String.format("%012x", rnd.nextLong() & 0xFFFFFFFFFFFFL);
  }
}
