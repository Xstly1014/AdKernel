# 协议 03 · 模拟账单（T+1 日级文件）

> 版本：v1.0（M0 · 2026-10-19）
> 方向：媒体 → 广告主（文件交付，非接口）
> 产出者：媒体模拟器账单生成器（日终汇总回传 cost → 账单文件）
> 意义：对账（M3）的对手方数据。预算防线没有对账兜底不完整——"计费对不上是广告系统 P0 事故"。

---

## 1. 文件规格

- **路径**：`data/billing/bill-YYYY-MM-DD.json`（当日最新运行覆盖；每次运行副本留存于 `data/runs/<runId>/bill-YYYY-MM-DD.json`）
- **粒度**：T+1 日级 × 计划级（自然日按媒体本地时区 00:00 切分）
- **格式**：JSON Lines 风格的记录数组，每行一条计划级汇总

```json
[
  {
    "date": "2026-10-19",
    "campaignId": "cmp-001",
    "totalCost": 12345.67,
    "impressions": 987654,
    "clicks": 19753
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| date | string(YYYY-MM-DD) | 账单归属自然日 |
| campaignId | string | 计划维度汇总 |
| totalCost | double(2 位小数) | `SUM(去重后 CLICK 事件 cost)`，四舍五入到分 |
| impressions | long | `COUNT(去重后 EXPOSURE 事件)` |
| clicks | long | `COUNT(去重后 CLICK 事件)` |

## 2. 汇总口径（与 M3 对账四桶直接咬合）

1. **计费口径**：CPC，只累计 CLICK 事件的 cost（协议 02 §2）。
2. **去重口径**：按 `requestId + eventType` 去重（媒体重试产生的重复回传只计一次）——模拟器账单生成器内置此兜底；M3 对账桶④（重复计费）的注入实验即故意破坏此口径。
3. **归属窗口**：按**事件 eventTime** 所在自然日归属（不是请求 requestTime）——23:59:59 的消耗归昨天，跨零点请求的点击若发生在零点后则归新账单日。此口径 M3 跨天边界测试的基准。
4. **舍入口径**：totalCost 逐事件累加后一次性四舍五入到 2 位小数（避免逐笔舍入累积误差）。

## 3. 对账预告（M3 实装，此处锁定口径）

| 对账桶 | 定义 | 定位手段 |
|---|---|---|
| 桶① 账单有自记无 | 媒体计了钱、AdKernel 没收到事件 | 按 requestId 反查事件日志，检查回传丢失 |
| 桶② 自记有账单无 | AdKernel 收到了、媒体没计入 | 归属窗口错位（跨零点）/ 去重口径不一致 |
| 桶③ 金额不一致 | 同一 requestId 两边 cost 不同 | 计费单价口径 / 舍入规则 |
| 桶④ 重复计费 | 同 requestId+eventType 计多次 | 幂等去重失效（人为注入实验的靶子） |

**验收口径（M3）**：账单差异 = 0；人为注入差异（重复回传/乱序）能定位到根因桶。

## 4. M0 验收要点（DoD k09）

- 模拟器运行结束后自动生成账单文件，`totalCost` 与运行期回传的 CLICK 事件 cost 总额一致（可对上）
- 账单 `clicks` ≤ `impressions`（勾稽不变式）
- 与 AdKernel 侧自记（`data/rta/rta-events.jsonl`）的 CLICK 聚合值一致（M0 即做到差异 = 0，为 M3 打底）
