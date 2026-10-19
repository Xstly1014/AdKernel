# 协议 01 · RTA 决策请求 / 响应

> 版本：v1.0（M0 · 2026-10-19）
> 方向：请求 = 媒体 → AdKernel；响应 = AdKernel → 媒体
> 对齐业界实证：华为鲸鸿动能 RTA 接口（HmacSha256 鉴权、priceWeight、expId、transUniqueId）、腾讯 RTA&ADX 对接指引（超时率红线）。
> 端点：`POST /api/rta/decision`（Content-Type: application/json）

---

## 1. 设计主线：requestId 贯穿

**协议宪法第一条**：每次决策请求的 `requestId`（UUID）被响应、曝光、点击、转化、账单明细五类记录共同携带。它是三条链路的共同钥匙：

| 链路 | 用法 | 里程碑 |
|---|---|---|
| 归因 | 转化事件按 requestId 反查决策（末次点击） | M4 |
| 幂等 | 重复回传按同 requestId 去重 | M3 |
| 对账 | 自记消耗与账单明细按 requestId 级比对 | M3 |

实现纪律：回传事件**构造时闭包捕获** requestId，禁止重新生成；requestId 必须随机（UUID/雪花），禁止自增整数（可预测 + 分布式部署冲突）。

**协议宪法第二条**：expId 闭环。AdKernel 在响应里告知实验分组 → 媒体回传事件时**原样带回** → 归因按 expId 分组。媒体的实验分组必须广告主自己透传，媒体不会替你记。

---

## 2. 决策请求（媒体 → AdKernel）

```json
{
  "requestId": "req-8f3a2b1c-9d4e-4f6a-8b7c-1d2e3f4a5b6c",
  "requestTime": 1763011200000,
  "campaignId": "cmp-001",
  "userId": "u-7c2e9a1f4b8d",
  "adSlotId": "slot-feed-1001",
  "scene": "feed",
  "sign": "a3f5b8..."
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| requestId | string(UUID) | ✓ | 全链路贯穿 ID = 归因的钥匙；媒体重试时携带同 ID |
| requestTime | long | ✓ | 请求时间戳（毫秒，UTC）；参与签名 + 新鲜度校验 |
| campaignId | string | ✓ | 广告计划 ID（M0 单计划 `cmp-001`；M2 起多计划路由） |
| userId | string | ✓ | 脱敏用户哈希 ID（对齐华为 oaid/ifa 脱敏传输口径） |
| adSlotId | string | ✓ | 广告位 ID（slot-feed-1001 / slot-splash-2001） |
| scene | string | ✓ | 场景（`feed` 信息流 / `splash` 开屏），场景化策略输入 |
| sign | string | ✓ | HmacSha256 签名，见 §4 |

## 3. 决策响应（AdKernel → 媒体）

```json
{
  "code": 0,
  "target": true,
  "bidRatio": 1.5,
  "expId": "exp-01-bucketA",
  "reason": "high_value_silent"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| code | int | 0 正常；非 0 见 §5 错误码（限流/降级专用码为 M3 伏笔） |
| target | boolean | 是否参竞（false = 本次退出竞价） |
| bidRatio | double | 出价系数（对齐华为 priceWeight）；范围 [0.1, 10.0]，echo 恒 1.0 |
| expId | string | 实验组透传（媒体须在回传事件中原样带回）|
| reason | string | 决策原因码：调试 + M4 归因细分素材（如 `echo` / `blacklist` / `high_value_silent` / `already_converted`） |

**超时口径**：媒体侧 60ms 判超时（主流媒体 60-100ms 硬限）；超时按媒体默认策略处理（不参竞）。超时率 > 1% 触发红线告警（对齐腾讯清退红线 5% 留 5 倍安全边际）。

## 4. 鉴权：HmacSha256 签名

```
sign = hex_lower( HmacSha256( requestId + requestTime + userId, secretKey ) )
```

- 签名覆盖请求体三个关键字段：防篡改（改任何一字段则签名失配）+ 防重放（requestTime 参与签名，且新鲜度校验独立于签名）。
- 新鲜度窗口：`|now - requestTime| > signTtlMs`（默认 60s）→ 401。覆盖媒体重试的时间尺度（秒级）。
- 密钥：M0 模拟器与 echo 服务共享 `secretKey`（配置文件，不进协议文档仓库）。

## 5. 错误码

| code | HTTP | 场景 | 说明 |
|---|---|---|---|
| 0 | 200 | 正常决策 | target/bidRatio 有效 |
| 40001 | 400 | 字段校验失败 | 必填缺失 / 类型非法 / 枚举越界 |
| 40101 | 401 | 签名错误 | 篡改签名 → 401 |
| 40102 | 401 | 时间戳过期 | 过期 requestTime → 401（即使签名本身正确） |
| (预留) 42901 | 429 | 限流 | M3 令牌桶过载，媒体侧不重试风暴 |
| (预留) 50301 | 503 | 降级总开关 | M3 一键全量退出参竞 |

## 6. 样例集

**正常参竞**（HTTP 200）
```json
{ "code": 0, "target": true, "bidRatio": 1.0, "expId": "exp-m0-echo", "reason": "echo" }
```

**黑名单拒绝**（HTTP 200）
```json
{ "code": 0, "target": false, "bidRatio": 0, "expId": "exp-m0-echo", "reason": "blacklist" }
```

**签名错误**（HTTP 401）
```json
{ "code": 40101, "target": false, "bidRatio": 0, "expId": null, "reason": "invalid_signature" }
```

**时间戳过期**（HTTP 401）
```json
{ "code": 40102, "target": false, "bidRatio": 0, "expId": null, "reason": "stale_request_time" }
```

## 7. 契约测试要点（M0 DoD k04）

- 篡改签名（改 sign 一位）→ HTTP 401 / code 40101
- 过期时间戳（requestTime = now - 10min，签名对过期值正确计算）→ HTTP 401 / code 40102
- 缺失必填字段 → HTTP 400 / code 40001

验证方式：`media-simulator` 的 `authcheck` 子命令三探针自动断言。
