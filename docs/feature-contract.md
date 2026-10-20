# AdKernel 特征契约（M1）

> 特征是决策的原料。本文档锁定特征模型、生成规则与消费方映射——M2 策略引擎的每个字段都能说出消费方，无一"悬空字段"（DoD k02）。

## 1. 特征模型（UserProfile，6 字段）

```java
public record UserProfile(
  String userId,            // 用户唯一标识（与 M0 用户池同规则生成）
  MemberLevel memberLevel,  // 会员等级：NORMAL / MEMBER / VIP
  boolean blacklisted,      // 黑名单（M0 静态名单的特征化承载）
  long lastConvertTime,     // 最近转化时间（epoch ms；0 = 从未转化）
  int impressionCount7d,    // 近 7 天曝光计数（用户×计划口径，M2 频控基础）
  long firstSeenTime        // 首次出现时间（epoch ms；新用户冷启动识别）
)
```

## 2. 字段 × 消费方映射（无一悬空）

| 字段 | 消费方 | 语义 |
|---|---|---|
| `userId` | 全链路 | requestId 闭包之外的第二个贯穿键：特征键、频控键、归因关联键 |
| `memberLevel` | **M2** 价值分层出价（VIP 1.5x / MEMBER 1.2x）；M4 实验分层的分层维度 | 高价值用户高出价 |
| `blacklisted` | **M1** 决策拒绝（`reason=blacklist`）；M0 裁判断言的真值承载 | 黑名单不参竞 |
| `lastConvertTime` | **M2** "已购未满 7d 不参竞"；**M4** 转化回流更新的目标字段 | 已购静默期 |
| `impressionCount7d` | **M2** 频控（用户×计划 ≥ N 次不展示） | 频次控制 |
| `firstSeenTime` | **M2** 新用户冷启动（注册 < 3d 走保守策略） | 冷启动识别 |

## 3. 画像源生成规则（确定性、可复现）

画像源规模 **50 万**，其中前 10 万与 M0 流量池完全重合（同一确定性规则），后 40 万为扩展用户（有画像、无流量——布隆预热需要全量集合）。

| 规则 | 定义 | 说明 |
|---|---|---|
| userId | `"u-" + hex(sha256("adkernel\|user\|" + i)).substring(0,12)` | 与 `UserPool` 同规则，i ∈ [0, 500000) |
| 黑名单 | `i % 100 == 0` → `blacklisted=true` | 与 M0 池真值**逐用户一致**（裁判不断言口径漂移） |
| 无画像长尾 | `i % 10 == 7 且 i % 100 != 0` → 跳过生成 | 10% 合法无画像；**黑名单用户必有画像**（否则默认画像会误放行黑名单） |
| 会员分布 | `h(i) % 100 < 3` → VIP；`< 10` → MEMBER；否则 NORMAL | h = sha256 确定性哈希；会员 10%（VIP 3%） |
| 7d 内转化 | `h(i) % 100 < 5` → `lastConvertTime = EPOCH_BASE + h'(i) % 7d` | 5%；EPOCH_BASE 固定常量保证跨启动可复现 |
| 曝光计数 | `h'(i) % 51` | 0~50，确定性 |
| 首次出现 | `h(i) % 100 < 5` → EPOCH_BASE 近 3d；否则 1 年内 | 新用户约 5% |

确定性哈希 `h(i) = sha256("adkernel|profile|" + i)` 的前 8 字节取 long——所有分布由 i 单向推导，**固定 seed 等价于固定 i 空间**，无随机状态、跨进程跨重启完全可复现。

## 4. 设计决策（ADR 摘要）

### D1：L1 内嵌 RTA 进程，L2/布隆/空值/源在特征服务（独立进程 9200）

三级延迟账本的口径要求 L1 命中 P99 < 0.1ms——只有进程内缓存可达（跨进程 RPC 本地 RTT ~1ms）。特征服务承载"广告主一方数据"角色：布隆前置 → 空值缓存 → L2 → 源，对 RTA 暴露统一查询接口。多实例 RTA 时各自持有 L1，共享特征服务的 L2——这是业界真实拓扑（决策方本地缓存 + 远端特征层）。

### D2：L2 用进程内模拟 Redis，而非真 Redis

- 延迟口径可控：注入 1ms RTT（模拟本地 Redis 网络往返），压测可复现；
- 零环境依赖（Windows 开发环境无需安装 Redis/Docker）；
- 接口对齐 Redis 语义（GET/SET + TTL + 随机偏移），生产切换成本低；
- 提供 hang 注入能力（k09 实验要件）——真 Redis 反而难以精确注入"整体 hang 30s"。
- 代价：丢失真实 Redis 的网络抖动/序列化开销真实感——在 ADR 中明示，M5 压测若需真实感可替换为实现同一接口的真 Redis 客户端。

### D3：手写布隆（fpp=0.01%）

50 万 key、fpp 0.01% → 位图 m ≈ 9.58M bits（~1.2MB）、13 个哈希函数。手写而非引入 Guava：项目零外部中间件依赖原则 + 布隆实现本身就是面试叙事。双缓冲热重构（实证案例）本场景不需要——50 万集合固定，前提条件成立。

### D4：RTA L1 不缓存"不存在"结果（防缓存污染）

伪造 ID 是 2^48 空间的随机值，几乎不重复——若 L1 缓存 notExist 结果，10 万容量的 L1 会被随机 ID 洗掉热用户（W-TinyLFU 也扛不住纯随机流的频率稀释）。纪律：L1 只缓存"有画像"条目；notExist 每次走特征服务（布隆秒拦，成本 ≈ 1ms RPC）。合法无画像用户由特征服务空值缓存兜底（30-60s TTL）。

### D5：默认画像 = 保守不参竞

降级链尽头（L2 hang + L1 无旧值）与无画像用户（布隆误判/长尾）统一保守处理：`target=false, reason=no_profile|degraded_default`。代价：M1 起参竞率 ≈ 89%（90% 合法 × 99% 非黑名单），漏斗勾稽口径不变（winRate 是条件概率：胜出/参竞）。

## 5. 三级延迟账本（验收口径）

| 层级 | 命中延迟（P99） | 容量 | 角色 |
|---|---|---|---|
| L1 Caffeine（RTA 进程内） | < 0.1ms | 30 万条（k10 实测反推，见 §6） | 消化 80%+ 真实流量；Redis 抖动时兜底（stale 旧值） |
| L2 模拟 Redis（特征服务） | ≈ 1-2ms（RTT 1ms + 查询） | 近全量（TTL 5min + jitter 0-60s） | 多实例共享层；回源挡板 |
| 源（画像源，特征服务内） | ≈ 6-8ms（5ms 注入 + 开销） | 50 万全量 | 回源成本基准 |
| **加权总延迟** | **≈ 2ms** | — | 对照：bypass 直查源 ≈ 12ms → **12ms → 2ms** |

## 6. Caffeine 参数卡（RTA L1）

| 参数 | 值 | 依据 |
|---|---|---|
| maximumSize | 300,000 | k10 实测反推（手册"容量翻倍找拐点"路径）：真实可命中流量 = 90% 真实（fake 按 D4 永不入 L1）× 90% 有画像（无画像长尾按 D4 永不入 L1）× topShare(K)。zipf s=0.86/N=50 万：K=10 万 → topShare 76.4%（上限 68.7%，实测 67.1% ✓ 模型验证）；K=20 万 → 85.9%（上限 77.3% < DoD 80%）；K=30 万 → 91.9%（上限 82.7%）；~200B/条 ≈ 60MB < 堆 30% 界 |
| refreshAfterWrite | 15s | 异步刷新、刷新期旧值可读（stale 兜底的一半） |
| expireAfterWrite | 10min | 旧值可读性上界；比 L2（5min）长，避免同时失效 |
| recordStats | 开 | 命中率/驱逐/加载时长——验收与 M5 面板数据源 |
| executor | 独立刷新池 | 刷新不挤占请求线程 |

## 7. 降级链（超时预算 3ms L2 / 5ms 总）

```
L1 miss → RPC 特征服务（3ms 超时）
  ├─ 成功 → 回填 L1 → 返回（degraded=none）
  ├─ 超时/失败 → 读 L1 旧值（stale，degraded=stale）※ refreshAfterWrite 10min 内旧值可读
  └─ L1 也无（冷 key）→ DEFAULT_PROFILE 保守不参竞（degraded=default）
每次降级打点：degradedStale / degradedDefault 计数（k10 观测要件）
```

stale 语义正确性：stale 命中黑名单用户照样拒（旧值 ≠ 错值——15s 刷新节奏内特征几乎总是对的）。
