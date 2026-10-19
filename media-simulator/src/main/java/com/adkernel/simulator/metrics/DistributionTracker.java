package com.adkernel.simulator.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * 流量分布统计（幂律验证，DoD k06）：按池内下标计数（伪造 ID 不计入——分布检验只看真实池用户）。
 */
public final class DistributionTracker {

  private final LongAdder[] counts;
  private final LongAdder realTotal = new LongAdder();
  private final LongAdder fakeTotal = new LongAdder();

  public DistributionTracker(int poolSize) {
    this.counts = new LongAdder[poolSize];
    for (int i = 0; i < poolSize; i++) {
      counts[i] = new LongAdder();
    }
  }

  public void record(int poolIndex, boolean fake) {
    if (fake) {
      fakeTotal.increment();
    } else {
      counts[poolIndex].increment();
      realTotal.increment();
    }
  }

  /** 实测 Top20% 用户流量占比（真实池用户口径） */
  public double measuredTop20Share() {
    long total = realTotal.sum();
    if (total == 0) {
      return 0;
    }
    long[] copy = new long[counts.length];
    for (int i = 0; i < counts.length; i++) {
      copy[i] = counts[i].sum();
    }
    Arrays.sort(copy);
    int k = (int) Math.ceil(counts.length * 0.2);
    long top = 0;
    for (int i = copy.length - 1; i >= copy.length - k; i--) {
      top += copy[i];
    }
    return (double) top / total;
  }

  /**
   * 出现过的 distinct 用户数。口径适用性：请求量不足时（distinct &lt; 池的 20%），
   * Top20% 桶必然涵盖全部请求（share 恒为 1.0），该指标不具判读意义，报告应标 n/a。
   */
  public int observedUsers() {
    int distinct = 0;
    for (LongAdder c : counts) {
      if (c.sum() > 0) {
        distinct++;
      }
    }
    return distinct;
  }

  /** Top20% 指标是否可判读（distinct 覆盖达到桶大小才有效） */
  public boolean top20Measurable() {
    return observedUsers() >= (int) Math.ceil(counts.length * 0.2);
  }

  /** 伪造 ID 请求占比 */
  public double measuredFakeRatio() {
    long total = realTotal.sum() + fakeTotal.sum();
    return total == 0 ? 0 : (double) fakeTotal.sum() / total;
  }

  public long realTotal() {
    return realTotal.sum();
  }
}
