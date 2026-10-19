package com.adkernel.simulator.run;

import com.adkernel.common.protocol.BillRecord;
import com.adkernel.common.protocol.CallbackEvent;
import com.adkernel.common.util.Json;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 对账预演（recon，M3 正式对账模块的前身）：
 * 侧1 = AdKernel 自记事件（rta-events.jsonl，按 requestId+eventType 去重聚合）；
 * 侧2 = 媒体账单（data/runs 下各 run 目录的 bill-*.json 汇总）。
 * 两侧 totalCost/impressions/clicks 差异=0 → PASS。
 */

public final class ReconTool {

  private static final class Acc {
    long impressions;
    long clicks;
    double cost;
  }

  private ReconTool() {
  }

  public static int run(String eventsFile, String billsDir) {
    try {
      // 侧1：自记事件聚合
      Map<String, Acc> self = new HashMap<>();
      Set<String> dedup = new HashSet<>();
      long lines = 0;
      long badLines = 0;
      long duplicates = 0;
      try (BufferedReader br = Files.newBufferedReader(Paths.get(eventsFile), StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          lines++;
          CallbackEvent e = Json.read(line, CallbackEvent.class);
          if (e == null) {
            badLines++;
            continue;
          }
          if (!dedup.add(e.requestId() + "|" + e.eventType())) {
            duplicates++;
            continue;
          }
          Acc acc = self.computeIfAbsent(e.campaignId(), k -> new Acc());
          switch (e.eventType()) {
            case EXPOSURE -> acc.impressions++;
            case CLICK -> {
              acc.clicks++;
              acc.cost += e.cost();
            }
            case CONVERT -> {
              /* 转化不计费 */ }
          }
        }
      }

      // 侧2：账单汇总
      Map<String, Acc> bill = new HashMap<>();
      long billFiles = 0;
      try (Stream<Path> walk = Files.walk(Paths.get(billsDir))) {
        List<Path> files = walk.filter(p -> p.getFileName().toString().startsWith("bill-")
            && p.getFileName().toString().endsWith(".json")).sorted().toList();
        for (Path f : files) {
          billFiles++;
          BillRecord[] records = Json.mapper().readValue(Files.readString(f, StandardCharsets.UTF_8),
              BillRecord[].class);
          for (BillRecord r : records) {
            Acc acc = bill.computeIfAbsent(r.campaignId(), k -> new Acc());
            acc.impressions += r.impressions();
            acc.clicks += r.clicks();
            acc.cost += r.totalCost();
          }
        }
      }

      System.out.printf("[recon] self-side events: lines=%d dedup=%d duplicates=%d bad=%d campaigns=%d%n",
          lines, dedup.size(), duplicates, badLines, self.size());
      System.out.printf("[recon] bill-side: files=%d campaigns=%d%n", billFiles, bill.size());

      boolean pass = true;
      System.out.println("[recon] campaign | self(cost/imp/click) | bill(cost/imp/click) | diff");
      Set<String> campaigns = new HashSet<>(self.keySet());
      campaigns.addAll(bill.keySet());
      for (String cid : campaigns.stream().sorted().toList()) {
        Acc s = self.getOrDefault(cid, new Acc());
        Acc b = bill.getOrDefault(cid, new Acc());
        double dCost = round2(b.cost) - round2(s.cost);
        long dImp = b.impressions - s.impressions;
        long dClick = b.clicks - s.clicks;
        boolean ok = Math.abs(dCost) < 0.005 && dImp == 0 && dClick == 0;
        pass &= ok;
        System.out.printf("[recon] %s | %.2f / %d / %d | %.2f / %d / %d | cost%+.2f imp%+d click%+d : %s%n",
            cid, round2(s.cost), s.impressions, s.clicks,
            round2(b.cost), b.impressions, b.clicks, dCost, dImp, dClick, ok ? "OK" : "DIFF");
      }
      System.out.println("[recon] overall: " + (pass ? "PASS (差异=0)" : "FAIL (存在差异)"));
      return pass ? 0 : 1;
    } catch (Exception e) {
      System.err.println("[recon] error: " + e.getMessage());
      e.printStackTrace();
      return 1;
    }
  }

  private static double round2(double v) {
    return Math.round(v * 100.0) / 100.0;
  }
}
