package com.adkernel.simulator.run;

import com.adkernel.simulator.judge.Judge;
import com.adkernel.simulator.metrics.PerSecondRecorder;

import java.nio.file.Path;
import java.util.Map;

/** 单轮运行结果（裁判快照 + 延迟统计 + 指标文件路径） */
public record RoundResult(
        Judge judge,
        PerSecondRecorder.Stats overall,
        Map<String, PerSecondRecorder.Stats> byPhase,
        Path metricsFile
) {
}
