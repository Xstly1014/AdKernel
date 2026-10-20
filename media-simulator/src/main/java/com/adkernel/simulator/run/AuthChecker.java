package com.adkernel.simulator.run;

import com.adkernel.common.auth.HmacSigner;
import com.adkernel.common.protocol.RtaRequest;
import com.adkernel.common.protocol.RtaResponse;
import com.adkernel.simulator.config.AppConfig;
import com.adkernel.simulator.http.ApiResult;
import com.adkernel.simulator.http.RtaClient;

import java.util.UUID;

/**
 * 鉴权契约探针（DoD k04）：
 * ① 合法请求 → 200/code 0；② 篡改签名 → 401/40101；③ 过期时间戳（签名对过期值正确）→ 401/40102；
 * ④ 缺失必填字段 → 400/40001。
 */
public final class AuthChecker {

  private AuthChecker() {
  }

  public static int run(AppConfig cfg) {
    try (RtaClient client = new RtaClient(2_000)) {
      String url = cfg.endpoints().decisionUrl();
      String secret = cfg.auth().secretKey();
      // M1：合法请求须来自画像池内真实用户（i=1：非黑名单、有画像），伪造 ID 会被布隆拦成 no_profile
      String userId = com.adkernel.common.user.UserIds.userId(1);

      // ① 合法请求（两次：冷请求断言鉴权契约 200/code0——M1 语义允许首载降级 no_profile；
      // 异步装载在 100ms RPC 硬超时内完成并回填 L1，第二次断言 L1 命中 + 决策参竞）
      long now = System.currentTimeMillis();
      String rid = "req-" + UUID.randomUUID();
      String sign = HmacSigner.sign(rid, now, userId, secret);
      RtaRequest valid = new RtaRequest(rid, now, "cmp-001", userId, "slot-feed-1001", "feed", sign);
      ApiResult r1 = client.postJson(url, valid);
      RtaResponse b1 = client.parseDecision(r1);
      boolean p1a = r1.httpOk() && b1 != null && b1.code() == 0;
      try {
        Thread.sleep(200);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      long now2 = System.currentTimeMillis();
      String rid2 = "req-" + UUID.randomUUID();
      String sign2 = HmacSigner.sign(rid2, now2, userId, secret);
      RtaRequest valid2 = new RtaRequest(rid2, now2, "cmp-001", userId, "slot-feed-1001", "feed", sign2);
      ApiResult r1b = client.postJson(url, valid2);
      RtaResponse b1b = client.parseDecision(r1b);
      boolean p1 = p1a && r1b.httpOk() && b1b != null && b1b.code() == 0 && b1b.target();

      // ② 篡改签名（正确签名末位翻转）
      String tampered = flipLastHex(sign);
      RtaRequest badSign = new RtaRequest(rid, now, "cmp-001", userId, "slot-feed-1001", "feed", tampered);
      ApiResult r2 = client.postJson(url, badSign);
      RtaResponse b2 = client.parseDecision(r2);
      boolean p2 = r2.status() == 401 && b2 != null && b2.code() == RtaResponse.CODE_BAD_SIGN;

      // ③ 过期时间戳（requestTime = now-10min，签名对该过期值正确计算——证明 TTL 独立于签名生效）
      long stale = now - 600_000;
      String rid3 = "req-" + UUID.randomUUID();
      String sign3 = HmacSigner.sign(rid3, stale, userId, secret);
      RtaRequest staleReq = new RtaRequest(rid3, stale, "cmp-001", userId, "slot-feed-1001", "feed", sign3);
      ApiResult r3 = client.postJson(url, staleReq);
      RtaResponse b3 = client.parseDecision(r3);
      boolean p3 = r3.status() == 401 && b3 != null && b3.code() == RtaResponse.CODE_STALE_TIME;

      // ④ 缺失必填字段（scene 为空；签名只覆盖 requestId/time/userId，故签名本身有效）
      String rid4 = "req-" + UUID.randomUUID();
      String sign4 = HmacSigner.sign(rid4, now, userId, secret);
      RtaRequest missing = new RtaRequest(rid4, now, "cmp-001", userId, "slot-feed-1001", null, sign4);
      ApiResult r4 = client.postJson(url, missing);
      RtaResponse b4 = client.parseDecision(r4);
      boolean p4 = r4.status() == 400 && b4 != null && b4.code() == RtaResponse.CODE_BAD_FIELD;

      System.out.printf("[authcheck] valid(cold)  -> http=%d code=%s target=%s : %s%n",
          r1.status(), b1 == null ? "-" : b1.code(), b1 == null ? "-" : b1.target(), p1a ? "PASS" : "FAIL");
      System.out.printf("[authcheck] valid(warm)  -> http=%d code=%s target=%s : %s%n",
          r1b.status(), b1b == null ? "-" : b1b.code(), b1b == null ? "-" : b1b.target(),
          (r1b.httpOk() && b1b != null && b1b.code() == 0 && b1b.target()) ? "PASS" : "FAIL");
      System.out.printf("[authcheck] tamperedSign -> http=%d code=%s : %s%n",
          r2.status(), b2 == null ? "-" : b2.code(), p2 ? "PASS" : "FAIL");
      System.out.printf("[authcheck] staleTime    -> http=%d code=%s : %s%n",
          r3.status(), b3 == null ? "-" : b3.code(), p3 ? "PASS" : "FAIL");
      System.out.printf("[authcheck] missingField -> http=%d code=%s : %s%n",
          r4.status(), b4 == null ? "-" : b4.code(), p4 ? "PASS" : "FAIL");

      boolean all = p1 && p2 && p3 && p4;
      System.out.println("[authcheck] overall: " + (all ? "PASS" : "FAIL"));
      return all ? 0 : 1;
    } catch (Exception e) {
      System.err.println("[authcheck] error: " + e.getMessage());
      return 1;
    }
  }

  private static String flipLastHex(String hex) {
    char last = hex.charAt(hex.length() - 1);
    char flipped = last == '0' ? '1' : '0';
    return hex.substring(0, hex.length() - 1) + flipped;
  }
}
