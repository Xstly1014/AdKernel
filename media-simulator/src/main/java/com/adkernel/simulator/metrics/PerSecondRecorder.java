package com.adkernel.simulator.metrics;

import com.adkernel.simulator.traffic.QpsCurve;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 秒级指标记录器：每秒一行 JSONL（ts/sec/phase/qps/p50/p99/errRate/timeoutRate）——延迟基线落盘（DoD
 * k10）。
 *
 * <p>
 * 口径纪律（与 M5 一致，从 M0 统一）：
 * 预热段照写但标记 warmup=true，统计与基线剔除；延迟分位数只统计已应答请求
 * （超时请求的时延在 60ms 处截尾，混入会污染分位数）；洪峰相位按 curve 自然分桶。
 * </p>
 */
public final class PerSecondRecorder implements AutoCloseable {

  private final QpsCurve curve;
  private final long startMs;
  private final Path file;
  private final BufferedWriter writer;

  // 当前秒累积槽（record 与 flusher 均持锁访问）
  private long curSec = -1;
  private long[] curLats = new long[1024];
  private int curLatN = 0;
  private long curCount, curErr, curTimeout;

  // 预热后聚合（报告用）
  private final Stats overall = new Stats();
  private final Map<String, Stats> byPhase = new LinkedHashMap<>();

  private final Thread flusher;
  private volatile boolean stopped = false;

  /** 分位数统计块（延迟样本仅含已应答请求；方法级同步保证进度线程读取一致） */
  public static final class Stats {
    long[] lats = new long[4096];
    int n = 0;
    long count, err, timeout;

    synchronized void addLat(long latNanos) {
      if (n == lats.length) {
        lats = Arrays.copyOf(lats, n * 2);
      }
      lats[n++] = latNanos;
    }

    public long count() {
      return count;
    }

    public long err() {
      return err;
    }

    public long timeout() {
      return timeout;
    }

    public synchronized int samples() {
      return n;
    }

    /** 毫秒分位数 */
    public synchronized double p(double q) {
      if (n == 0) {
        return 0;
      }
      long[] copy = Arrays.copyOf(lats, n);
      Arrays.sort(copy);
      int idx = (int) Math.min(n - 1, Math.round(q * (n - 1)));
      return copy[idx] / 1_000_000.0;
    }

    public synchronized double maxMs() {
      if (n == 0) {
        return 0;
      }
      long m = 0;
      for (int i = 0; i < n; i++) {
        m = Math.max(m, lats[i]);
      }
      return m / 1_000_000.0;
    }
  }

  public PerSecondRecorder(QpsCurve curve, Path file) throws IOException {
    this.curve = curve;
    this.startMs = System.currentTimeMillis();
    this.file = file;
    Files.createDirectories(file.getParent());
    this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    this.flusher = Thread.ofVirtual().name("metrics-flusher").start(this::flushLoop);
  }

  /** 记录一次决策请求的客户端观测结果 */
  public synchronized void record(long latNanos, boolean err, boolean timedOut) {
    long sec = (System.currentTimeMillis() - startMs) / 1000;
    if (sec != curSec) {
      rollSeconds(sec);
    }
    curCount++;
    if (err) {
      curErr++;
    }
    if (timedOut) {
      curTimeout++;
    } else {
      if (curLatN == curLats.length) {
        curLats = Arrays.copyOf(curLats, curLatN * 2);
      }
      curLats[curLatN++] = latNanos;
    }
    // 预热后聚合（判罚不受预热豁免——正确性永远计入，这里只剔延迟统计）
    if (!curve.isWarmup(sec)) {
      Stats phase = byPhase.computeIfAbsent(curve.phaseNameAt(sec), k -> new Stats());
      overall.count++;
      if (err) {
        overall.err++;
      }
      if (timedOut) {
        overall.timeout++;
      } else {
        overall.addLat(latNanos);
        phase.addLat(latNanos);
      }
      phase.count++;
      if (err) {
        phase.err++;
      }
      if (timedOut) {
        phase.timeout++;
      }
    }
  }

  /** 把已完成的秒落盘并推进到目标秒 */
  private synchronized void rollSeconds(long targetSec) {
    if (curSec < 0) {
      curSec = targetSec;
      return;
    }
    while (curSec < targetSec) {
      emitLine(curSec, curCount, curErr, curTimeout, curLats, curLatN);
      curSec++;
      curCount = 0;
      curErr = 0;
      curTimeout = 0;
      curLatN = 0;
    }
  }

  private void emitLine(long sec, long count, long err, long timeout, long[] lats, int n) {
    String phase = curve.phaseNameAt(sec);
    double p50 = percentileMs(lats, n, 0.50);
    double p99 = percentileMs(lats, n, 0.99);
    double errRate = count == 0 ? 0 : (double) err / count;
    double timeoutRate = count == 0 ? 0 : (double) timeout / count;
    String line = "{\"ts\":" + (startMs + sec * 1000)
        + ",\"sec\":" + sec
        + ",\"phase\":\"" + phase + "\""
        + ",\"warmup\":" + curve.isWarmup(sec)
        + ",\"qps\":" + count
        + ",\"p50\":" + p50
        + ",\"p99\":" + p99
        + ",\"errRate\":" + errRate
        + ",\"timeoutRate\":" + timeoutRate + "}";
    try {
      writer.write(line);
      writer.newLine();
    } catch (IOException e) {
      throw new IllegalStateException("metrics write failed", e);
    }
  }

  private static double percentileMs(long[] lats, int n, double q) {
    if (n == 0) {
      return 0;
    }
    long[] copy = Arrays.copyOf(lats, n);
    Arrays.sort(copy);
    int idx = (int) Math.min(n - 1, Math.round(q * (n - 1)));
    return copy[idx] / 1_000_000.0;
  }

  private void flushLoop() {
    try {
      while (!stopped) {
        Thread.sleep(250);
        long nowSec = (System.currentTimeMillis() - startMs) / 1000;
        rollSeconds(nowSec); // 只落盘已完成的秒
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public Stats overall() {
    return overall;
  }

  public Map<String, Stats> byPhase() {
    return byPhase;
  }

  public long startMs() {
    return startMs;
  }

  public Path file() {
    return file;
  }

  @Override
  public synchronized void close() throws IOException {
    stopped = true;
    try {
      flusher.join(5_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    rollSeconds(curSec + 1); // 落盘最后一格未完成秒
    writer.flush();
    writer.close();
  }
}
