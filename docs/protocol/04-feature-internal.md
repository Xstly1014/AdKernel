# 协议 04 · RTA ↔ 特征服务内部协议

> 内部协议（非对媒体契约）。RTA 决策链的特征查询通道 + 实验注入通道。特征服务是"广告主一方数据"的角色模拟，独立进程（端口 9200）。

## 1. 特征查询

```
GET /internal/feature/{userId}
```

**响应**（200，三种语义）：

```json
// 有画像
{ "exist": true, "layer": "l2", "profile": {
    "userId": "u-3f9a2b1c4d5e",
    "memberLevel": "VIP",
    "blacklisted": false,
    "lastConvertTime": 1759276800000,
    "impressionCount7d": 12,
    "firstSeenTime": 1700000000000
} }

// 合法无画像（布隆通过但源无记录；空值缓存可命中）
{ "exist": false, "layer": "nullcache", "profile": null }

// 布隆拦截（"不存在"≈100% 准确——伪造 ID）
{ "exist": false, "layer": "bloom", "profile": null }
```

- `layer`：本次命中的层 `bloom / nullcache / l2 / source`——观测口径（k07/k08 断言依据）。
- 错误码：500（特征服务内部错误，RTA 侧按降级链处理）。

## 2. 注入开关（k09 实验要件）

```
POST /internal/admin/inject
{ "sourceLatencyMs": 50, "l2HangMs": 30000 }
```

- `sourceLatencyMs`：源层延迟注入（默认 5ms；0 = 不注入额外延迟）。
- `l2HangMs`：L2 hang 注入窗口（毫秒；期间 L2 get 全部阻塞至窗口结束）。
- `POST { "sourceLatencyMs": 5, "l2HangMs": 0 }` 解除注入。

## 3. 统计端点

```
GET /internal/admin/stats
```

```json
{
  "bloom":   { "intercepted": 123456, "passed": 987654 },
  "nullCache": { "hits": 234, "misses": 56 },
  "l2":      { "hits": 100000, "misses": 5000 },
  "source":  { "queries": 5000, "p50Ms": 5.1, "p99Ms": 5.8 },
  "inject":  { "sourceLatencyMs": 5, "l2HangUntil": 0 }
}
```

## 4. RTA 侧统计端点

```
GET /internal/stats          # L1 与降级链观测（k04/k05/k10 断言依据）
```

```json
{
  "l1": {
    "hitCount": 800000, "missCount": 100000, "hitRate": 0.889,
    "degradedStale": 12, "degradedDefault": 3,
    "featureRpcCount": 100012,        // 实际发起的特征 RPC 数（k05 合并断言）
    "featureLatencyP50Ms": 0.02, "featureLatencyP99Ms": 1.8,
    "cacheBypass": false
  }
}
```

```
POST /internal/admin/cache { "bypass": true|false }
```

- `bypass=true`：L1/L2 全部旁路，每次直查源层——12ms 对照基线的开关（k10）。

## 5. 设计要点

- **查询是同步 HTTP**（虚拟线程承载，RTA 侧 3ms 超时），不引入 gRPC——内部一跳，延迟预算内 HTTP 足够，且与项目技术栈一致。
- **协议无签名**：内网服务间调用（生产形态是 mTLS/VPC 隔离），模拟环境不做鉴权——但这必须在 ADR 明示，不是遗漏。
- **requestId 不透传**：特征查询是决策的子步骤，不产生独立事件记录（M4 若需特征查询审计再加）。
