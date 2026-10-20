package com.adkernel.rta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AdKernel RTA 服务端入口。
 *
 * <p>M0 形态：echo 骨架——HmacSha256 验签 + 协议校验 + 黑名单 + 恒参竞（bidRatio=1.0），
 * 并接收转化回传事件落盘自记（M3 对账的自记消耗源）。
 * M1 起：黑名单与画像判断改查特征服务（进程内 L1 Caffeine → 特征服务布隆/空值/L2/源 → 降级链），
 * 静态 UserPool 名单退役（特征源与 UserPool 同规则生成，裁判真值不变）。
 * 后续里程碑在本进程内长出：M2 策略引擎、M3 限流预算、M4 归因回流。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RtaApplication {

    public static void main(String[] args) {
        SpringApplication.run(RtaApplication.class, args);
    }
}
