package com.adkernel.rta.feature;

import com.adkernel.common.user.MemberLevel;
import com.adkernel.common.user.UserProfile;
import com.adkernel.common.util.LatencyRecorder;
import com.adkernel.rta.config.RtaProperties;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * L1 特征缓存（RTA 进程内 Caffeine）+ 超时降级链。
 *
 * <p>
 * 三级延迟账本要求 L1 命中 &lt; 0.1ms——只有进程内可达（ADR D1）。参数由数据反推
 * （契约 §6）：maximumSize=10 万（50 万 × 热头部 20% 幂律）、refreshAfterWrite=15s
 * （异步刷新、刷新期旧值可读——stale 兜底的一半）、expireAfterWrite=10min（旧值可读性上界）。
 * </p>
 *
 * <p>
 * 降级链（超时预算：RPC 3ms / 总 5ms）：L1 miss → RPC 特征服务 →
 * 超时/失败时：有旧条目（refresh 场景）Caffeine 返回旧值（请求无感，打 degradedStale）；
 * 冷 key 首载失败打 degradedDefault 并返回 null（调用方保守不参竞）。
 * </p>
 *
 * <p>
 * 纪律：L1 不缓存"不存在"结果（ADR D4）——伪造 ID 是 2^48 随机空间，
 * 缓存 notExist 会把 10 万容量洗成随机 ID 池。实现：null 包装为哨兵值穿透 Caffeine
 * 的非空约束，取到哨兵立即 invalidate（窗口内并发请求同样处理——singleflight 语义不破，
 * 特征服务侧空值缓存兜住重复回源）。
 * </p>
 */
@Service
public class FeatureCacheService {

  /** "不存在"哨兵（仅活在 Caffeine 值管道里，取到即清除） */
  private static final UserProfile SENTINEL = new UserProfile("__not_exist__", MemberLevel.NORMAL, false, 0, 0, 0);

  private final AsyncLoadingCache<String, UserProfile> l1;
  private final FeatureClient client;
  private final LongAdder degradedStale = new LongAdder();
  private final LongAdder degradedDefault = new LongAdder();
  private final LongAdder featureRpc = new LongAdder();
  /**
   * 装载失败退避表（k09 三跑根因修复）：key → 退避截止时刻（nanoTime）。
   *
   * <p>
   * 原语义：装载 RPC 超时（100ms 硬超时）→ Caffeine 移除条目 → 下一次同 key 请求立即再次装载。
   * L2 hang 注入期该正反馈引爆通道：3000 QPS miss 持续重试 → 7 万+ 并发 sendAsync →
   * FeatureClient 单 selector 饱和 → RTA 整体假死（决策响应一并停摆，phase 2/3 全灭），
   * 流量停止后才缓慢自愈。退避窗口 = RPC 硬超时的 20 倍（2s）：hang 30s 内每 key 至多 15 次
   * 装载尝试（原语义 ~300 次），风暴被压平；装载成功即清除退避，恢复零延迟。
   * </p>
   */
  private final java.util.concurrent.ConcurrentHashMap<String, Long> loadFailUntil = new java.util.concurrent.ConcurrentHashMap<>();
  private static final long BACKOFF_NANOS = java.time.Duration.ofSeconds(2).toNanos();
  /**
   * 装载 RPC 并发闸门（k09 四跑根因修复）：in-flight 装载 ≤ 200。
   *
   * <p>
   * hang 期风暴路径：miss/refresh 装载持续打特征服务 → 特征服务 20000 max-connections
   * 被 hang 的虚拟线程占满 → RTA 端数万连接尝试排队 → FeatureClient 提交段变慢 →
   * 决策线程在 {@code l1.get()} 的 loader 调用段被拖到 60ms 红线外（health 不走 L1 故
   * 毫秒级——决策走 L1 故全灭），模拟器侧观测为大面积超时。闸门 200 × 100ms 硬超时 =
   * 2000 装载/s 上限：稳态需求 ~1450/s 在限内（无感），风暴期超限装载立即以 failedFuture
   * 完成——进入 2s 退避，不占连接、不拖决策线程。
   * </p>
   */
  private static final int LOAD_PERMITS = 200;
  private final java.util.concurrent.Semaphore loadPermits = new java.util.concurrent.Semaphore(LOAD_PERMITS);
  private final LongAdder loadShedded = new LongAdder();
  /**
   * 装载断路器（k09 六跑根因修复）：连续失败 ≥20 → 全局熔断 10s → 半开探测。
   *
   * <p>
   * 闸门只限 in-flight 数，限不住「超时→连接作废→重建」的连接消耗率：200 并发 ×
   * 每秒 10 轮 = 2000 新建连接/s，L2 hang 30s 期间烧掉 6 万动态端口——Windows 端口池
   * （~16k）与 TIME_WAIT（120s）双双耗尽：特征服务侧 CLOSE_WAIT 堆积 1.5 万、模拟器
   * 决策超时引发自身连接重建风暴，最终全链路端口饥饿（phase 3 模拟器 ok=0 的真因——
   * 不是 RTA 慢，是模拟器无端口可用）。断路器在故障期把装载压制到「每 10s 一次探测」：
   * hang 30s ≈ 3 个连接，风暴无从谈起；探测成功即闭合，恢复零延迟。
   * </p>
   */
  private static final int CIRCUIT_FAIL_THRESHOLD = 20;
  private static final long CIRCUIT_OPEN_NANOS = java.time.Duration.ofSeconds(10).toNanos();
  private final java.util.concurrent.atomic.AtomicInteger consecLoadFails = new java.util.concurrent.atomic.AtomicInteger();
  private volatile long circuitOpenUntilNanos = 0L;
  private final LongAdder circuitOpenCount = new LongAdder();
  private final LongAdder loadFailBackoff = new LongAdder();
  private final LatencyRecorder latency = new LatencyRecorder(16384);
  private volatile boolean bypass;
  /** 独立刷新池：刷新不挤占请求线程（契约 §6）；亦承载 admin 全量失效（见 setBypass） */
  private final Executor refreshPool = Executors.newVirtualThreadPerTaskExecutor();

  public FeatureCacheService(FeatureClient client, RtaProperties props) {
    this.client = client;
    this.l1 = Caffeine.newBuilder()
        .maximumSize(props.l1MaxSize())
        .refreshAfterWrite(Duration.ofSeconds(props.l1RefreshSec()))
        .expireAfterWrite(Duration.ofMinutes(props.l1ExpireMin()))
        .recordStats()
        .executor(refreshPool)
        .buildAsync((key, executor) -> loadRpc(key));
  }

  /**
   * 查询：返回画像；null = 无画像/不存在/降级保守（调用方不参竞）。
   * bypass 模式每次直穿特征服务（12ms 对照基线，k10）。
   *
   * <p>
   * 降级链零等待（软预算语义收严为「不等 in-flight 装载」）：冷首载 6-8ms 本就超出
   * 5ms 用户可见预算，同步 join 等待是纯损耗——实测 k09 首跑中 139k miss 有 110k 请求的
   * {@code f.get(5ms)} 在三进程同机争抢下膨胀到装载全程（30-60ms+），60ms 媒体红线全面击穿。
   * 改为：future 已完成（L1 命中 / 回填完成 / refresh 旧值）立即取值；in-flight 立即降级返回
   * （k05 语义：冷装载期 200/code0 即合法）——装载仍在后台 100ms 硬超时内完成并回填，
   * 后续请求命中。
   * </p>
   */
  public UserProfile get(String userId) {
    long t0 = System.nanoTime();
    try {
      if (bypass) {
        UserProfile p = client.fetch(userId).join();
        latency.record((System.nanoTime() - t0) / 1_000_000.0);
        return p; // 不回填 L1——bypass 语义就是无缓存直查
      }
      // 失败退避窗口内：不再触发装载（防 hang 期重试风暴），直接降级
      Long failUntil = loadFailUntil.get(userId);
      if (failUntil != null) {
        if (t0 < failUntil) {
          loadFailBackoff.increment();
          degradedDefault.increment();
          latency.record((System.nanoTime() - t0) / 1_000_000.0);
          return null;
        }
        loadFailUntil.remove(userId, failUntil); // 窗口已过——清理并放行本次装载
      }
      CompletableFuture<UserProfile> f = l1.get(userId);
      if (!f.isDone()) {
        // in-flight 装载（冷首载/故障期）：不等——立即降级，装载后台完成后回填
        degradedDefault.increment();
        latency.record((System.nanoTime() - t0) / 1_000_000.0);
        return null;
      }
      UserProfile p = f.join(); // 已完成——无阻塞
      loadFailUntil.remove(userId); // 装载成功——清除退避
      if (p == SENTINEL) {
        l1.synchronous().invalidate(userId); // 纪律 D4：不缓存"不存在"
        latency.record((System.nanoTime() - t0) / 1_000_000.0);
        return null;
      }
      latency.record((System.nanoTime() - t0) / 1_000_000.0);
      return p;
    } catch (Exception ex) {
      // 装载 future 异常完成（RPC 超时/失败）：进退避窗口（防同 key 重试风暴）；
      // refresh 场景旧 entry 仍在（后续请求继续命中旧值）记 stale；冷 key 记 default
      loadFailUntil.put(userId, System.nanoTime() + BACKOFF_NANOS);
      if (l1.getIfPresent(userId) != null) {
        degradedStale.increment();
      } else {
        degradedDefault.increment();
      }
      latency.record((System.nanoTime() - t0) / 1_000_000.0);
      return null;
    }
  }

  private CompletableFuture<UserProfile> loadRpc(String key) {
    featureRpc.increment();
    // 断路器打开：全局暂停装载（故障期零连接消耗），miss 直接走降级链
    long now = System.nanoTime();
    if (now < circuitOpenUntilNanos) {
      return CompletableFuture.failedFuture(new IllegalStateException("circuit-open"));
    }
    if (!loadPermits.tryAcquire()) {
      // 闸门满：立即失败（非阻塞）——请求线程 isDone=true → 降级 + 2s 退避，不占连接资源
      loadShedded.increment();
      return CompletableFuture.failedFuture(new IllegalStateException("load-shed"));
    }
    return client.fetch(key)
        .thenApply(p -> p == null ? SENTINEL : p) // null 包装哨兵穿透 Caffeine 非空约束
        .whenComplete((r, e) -> {
          loadPermits.release();
          if (e != null) {
            if (consecLoadFails.incrementAndGet() >= CIRCUIT_FAIL_THRESHOLD) {
              // 连续失败达阈值：全局熔断 10s（半开=窗口过后放行首个请求探测）
              circuitOpenUntilNanos = System.nanoTime() + CIRCUIT_OPEN_NANOS;
              consecLoadFails.set(0);
              circuitOpenCount.increment();
            }
          } else {
            consecLoadFails.set(0); // 任一成功即重置——正常态偶发超时不会误开
          }
        });
  }

  public void setBypass(boolean b) {
    this.bypass = b;
    if (b) {
      // 切对照模式不留热手缓存——异步清除：30 万条目全量 invalidateAll 需 100ms+，
      // 同步执行会把 admin 端点拖过媒体 60ms 超时类（k10 era B 首跑暴露）。
      // bypass=true 路径本身不读 L1（client.fetch 直连），失效只需在下一个 cached
      // 纪元开始前完成——异步不破坏实验隔离语义。
      refreshPool.execute(() -> l1.synchronous().invalidateAll());
    }
  }

  public boolean bypass() {
    return bypass;
  }

  public java.util.Map<String, Object> stats() {
    CacheStats s = l1.synchronous().stats();
    double hitRate = s.requestCount() == 0 ? 0 : (double) s.hitCount() / s.requestCount();
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("hitCount", s.hitCount());
    m.put("missCount", s.missCount());
    m.put("hitRate", hitRate);
    m.put("evictions", s.evictionCount());
    m.put("degradedStale", degradedStale.sum());
    m.put("degradedDefault", degradedDefault.sum());
    m.put("loadFailBackoff", loadFailBackoff.sum());
    m.put("loadShedded", loadShedded.sum());
    m.put("circuitOpenCount", circuitOpenCount.sum());
    m.put("circuitOpenNow", System.nanoTime() < circuitOpenUntilNanos);
    m.put("featureRpcCount", featureRpc.sum());
    m.put("featureLatencyP50Ms", latency.p(0.50));
    m.put("featureLatencyP99Ms", latency.p(0.99));
    m.put("featureLatencySamples", latency.count());
    m.put("cacheBypass", bypass);
    return m;
  }
}
