package com.adkernel.simulator.billing;

import com.adkernel.common.protocol.BillRecord;
import com.adkernel.common.util.Json;
import com.adkernel.simulator.funnel.FunnelEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 账单生成器（T+1 模拟）：整运行聚合回传 cost → 计划级账单文件（协议 03）。
 * 去重口径已在 FunnelEngine 入账时执行（requestId+EventType）。
 */
public final class BillGenerator {

    private BillGenerator() {
    }

    public static List<BillRecord> generate(FunnelEngine funnel, LocalDate date) {
        List<BillRecord> records = new ArrayList<>();
        for (Map.Entry<String, FunnelEngine.BillAcc> e : funnel.billByCampaign().entrySet()) {
            FunnelEngine.BillAcc acc = e.getValue();
            records.add(new BillRecord(
                    date.toString(),
                    e.getKey(),
                    round2(acc.cost.sum()),
                    acc.impressions.sum(),
                    acc.clicks.sum()));
        }
        records.sort((a, b) -> a.campaignId().compareTo(b.campaignId()));
        return records;
    }

    public static void write(Path file, List<BillRecord> records) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(records), StandardCharsets.UTF_8);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
