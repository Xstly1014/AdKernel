package com.adkernel.rta.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * JSONL 异步落盘器：主链路零阻塞铁律——请求线程只做 queue.offer，写盘由独立虚拟线程完成。
 *
 * <p>
 * 这是 M3 对账"自记消耗"的持久化底座（M4 起切换 mini-MQ 管道，接口不变）。
 * 有界队列 + 丢弃计数：队列打满时丢弃并计数（正常水位远低于容量；丢弃数进日志）。
 * </p>
 */
public final class JsonlAppender implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(JsonlAppender.class);

  private static final int QUEUE_CAPACITY = 200_000;
  private static final long POLL_MS = 200;

  private final Path path;
  private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
  private final LongAdder dropped = new LongAdder();
  private final Thread writer;
  private volatile boolean closed = false;

  public JsonlAppender(Path path) {
    this.path = path;
    this.writer = Thread.ofVirtual().name("jsonl-writer-" + path.getFileName()).start(this::writeLoop);
  }

  /** 非阻塞追加；队列满则丢弃并计数 */
  public void append(String jsonLine) {
    if (!queue.offer(jsonLine)) {
      dropped.increment();
    }
  }

  public long droppedCount() {
    return dropped.sum();
  }

  public Path path() {
    return path;
  }

  private void writeLoop() {
    try (BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      while (!closed || !queue.isEmpty()) {
        String line = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
        if (line == null) {
          continue;
        }
        bw.write(line);
        bw.newLine();
        // 批量排水，减少系统调用
        String next;
        while ((next = queue.poll()) != null) {
          bw.write(next);
          bw.newLine();
        }
        bw.flush();
      }
      bw.flush();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      log.error("jsonl writer failed: {}", path, e);
    }
  }

  @Override
  public void close() {
    closed = true;
    try {
      writer.join(10_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (dropped.sum() > 0) {
      log.warn("jsonl appender dropped lines: file={}, count={}", path, dropped.sum());
    }
  }
}
