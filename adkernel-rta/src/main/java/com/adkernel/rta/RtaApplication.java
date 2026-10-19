package com.adkernel.rta;

import com.adkernel.common.user.UserPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * AdKernel RTA 服务端入口。
 *
 * <p>M0 形态：echo 骨架——HmacSha256 验签 + 协议校验 + 静态黑名单 + 恒参竞（bidRatio=1.0），
 * 并接收转化回传事件落盘自记（M3 对账的自记消耗源）。
 * 后续里程碑在本进程内长出：M1 特征多级缓存、M2 策略引擎、M3 限流预算、M4 归因回流。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RtaApplication {

    private static final Logger log = LoggerFactory.getLogger(RtaApplication.class);

    /** 用户池（黑名单静态名单的载体；确定性生成，与模拟器裁判共享同一真值规则） */
    @Bean
    public UserPool userPool() {
        UserPool pool = new UserPool(UserPool.DEFAULT_SIZE);
        log.info("user pool ready: size={}, blacklist={}", pool.size(), pool.blacklistSize());
        return pool;
    }

    public static void main(String[] args) {
        SpringApplication.run(RtaApplication.class, args);
    }
}
