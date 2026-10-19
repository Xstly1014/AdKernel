# 协议 02 · 转化回传（曝光 / 点击 / 转化事件）

> 版本：v1.0（M0 · 2026-10-19）
> 方向：媒体 → AdKernel（漏斗逐级上报）
> 端点：`POST /api/event`（Content-Type: application/json）
> 意义：这条管道是整条业务闭环的"回流血管"——M3 对账的自记消耗源、M4 归因与特征回流的输入。M0 打通它，后面里程碑只换更好的零件。

---

## 1. 事件模型（三类共用一个结构）

```json
{
  "requestId": "req-8f3a2b1c-9d4e-4f6a-8b7c-1d2e3f4a5b6c",
  "eventType": "CLICK",
  "eventTime": 1763011203120,
  "campaignId": "cmp-001",
  "expId": "exp-01-bucketA",
  "cost": 0.7
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| requestId | string(UUID) | ✓ | **与决策请求同一 ID**（贯穿设计，宪法第一条） |
| eventType | string | ✓ | 枚举：`EXPOSURE` / `CLICK` / `CONVERT` |
| eventTime | long | ✓ | 事件发生时间戳（毫秒，UTC） |
| campaignId | string | ✓ | 与决策请求一致 |
| expId | string | ✓ | **决策响应里的分组被媒体原样带回**（expId 闭环，宪法第二条） |
| cost | double | — | 计费金额；**仅 CLICK 事件携带**（CPC），EXPOSURE/CONVERT 恒 0 |

## 2. 计费口径（对账基准，M3 依赖）

**CPC 计费挂在点击事件上**——曝光/点击/转化三层漏斗，花钱在点击层。混淆计费节点（把 cost 挂到曝光或转化）是对账永远对不上的头号原因。自记消耗 = `SUM(CLICK 事件 cost)`。

## 3. 漏斗语义（媒体侧模拟口径）

```
决策响应 target=true
   │ winRate = 0.6        → 竞价胜出（简化：不模拟真实竞价，M5 归因说明裁剪）
   ▼
EXPOSURE 事件（cost=0）
   │ ctr = 0.02           → 信息流行业基准 1%-3%
   ▼
CLICK 事件（cost ∈ [0.3, 1.2] CPC）
   │ cvr = 0.03           → 电商行业基准 1%-5%
   ▼
CONVERT 事件（cost=0）
```

- 三个概率参数化进 `config/funnel.yaml`——M4 对照实验的"调节旋钮"：**基线组与实验组走同一套漏斗概率，唯一变量是决策逻辑**；概率硬编码则实验调不了，两组概率不同则是作弊。
- 勾稽不变式：`EXPOSURE 数 ≥ CLICK 数 ≥ CONVERT 数`（漏斗逐级递减）；冒烟脚本断言此不变式。
- 事件时序：M0 同步发（决策响应后立即按漏斗抽样逐级上报），M4 起引入真实时延（点击 +1~10s、转化 +5~60s，归因窗口需要）。

## 4. 样例集

**曝光**
```json
{ "requestId": "req-8f3a...", "eventType": "EXPOSURE", "eventTime": 1763011200050, "campaignId": "cmp-001", "expId": "exp-m0-echo", "cost": 0 }
```

**点击（计费）**
```json
{ "requestId": "req-8f3a...", "eventType": "CLICK", "eventTime": 1763011203120, "campaignId": "cmp-001", "expId": "exp-m0-echo", "cost": 0.7 }
```

**转化**
```json
{ "requestId": "req-8f3a...", "eventType": "CONVERT", "eventTime": 1763011215000, "campaignId": "cmp-001", "expId": "exp-m0-echo", "cost": 0 }
```

## 5. 响应

```json
{ "code": 0 }
```

| code | HTTP | 场景 |
|---|---|---|
| 0 | 200 | 已接收（异步落盘，不保证已持久化——M3 起走 mini-MQ 保障） |
| 40001 | 400 | 字段校验失败（requestId 缺失 / eventType 越界 / eventTime ≤ 0） |

## 6. 实现纪律（模拟器 + 未来真实媒体共同遵守）

1. 回传任务**构造时闭包捕获** requestId（及 expId/campaignId），禁止回传时重新生成或反查——异步上下文丢失关联是最大隐患。
2. requestId 全局禁止挪作业务主键以外用途（如排序依据）。
3. 重复回传（媒体重试）允许发生：AdKernel 侧幂等去重在 M3 落地（requestId+eventType 维度），M0 事件落盘不去重，账单生成侧按 requestId+eventType 去重兜底。

## 7. AdKernel 侧消费（本协议的下游）

| 消费方 | 用途 | 里程碑 |
|---|---|---|
| 事件落盘（JSONL 自记） | 对账的自记消耗源 | M0 起持续 |
| 归因引擎 | 转化按 requestId 反查决策（末次点击） | M4 |
| 特征近线回流 | 转化 → mini-MQ → 特征更新 → L2 失效（"已转化即排除" < 5s） | M4 |
