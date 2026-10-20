package com.adkernel.feature;

import com.adkernel.feature.bloom.BloomFilter;
import com.adkernel.feature.cache.L2Cache;
import com.adkernel.feature.cache.NullValueCache;
import com.adkernel.feature.config.FeatureProperties;
import com.adkernel.feature.source.ProfileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * AdKernel 特征服务入口（端口 9200）。
 *
 * <p>角色模拟"广告主一方数据"（订单库/会员库/行为日志）：RTA 决策链不能直连源，
 * 经由本服务的布隆前置 → 空值缓存 → L2（模拟 Redis，TTL 随机偏移）→ 源（5ms 延迟注入）
 * 读路径取特征。L1 Caffeine 在 RTA 进程内（三级延迟账本要求 L1 命中 &lt; 0.1ms，只有进程内可达）。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FeatureApplication {

    private static final Logger log = LoggerFactory.getLogger(FeatureApplication.class);

    @Bean
    public ProfileSource profileSource(FeatureProperties props) {
        long t0 = System.currentTimeMillis();
        ProfileSource source = new ProfileSource(props.poolSize(), props.sourceLatencyMs());
        log.info("profile source ready: {} users ({} skipped, no-profile tail), distribution: {}",
                source.size(), source.skippedCount(), source.distributionSummary());
        log.info("profile source init took {}ms", System.currentTimeMillis() - t0);
        return source;
    }

    @Bean
    public L2Cache l2Cache(FeatureProperties props) {
        return new L2Cache(props.l2TtlBaseMs(), props.l2TtlJitterMs());
    }

    @Bean
    public NullValueCache nullValueCache(FeatureProperties props) {
        return new NullValueCache(props.nullTtlBaseMs(), props.nullTtlJitterMs());
    }

    @Bean
    public BloomFilter bloomFilter(FeatureProperties props, ProfileSource source) {
        long t0 = System.currentTimeMillis();
        int total = source.size() + source.skippedCount();
        BloomFilter bloom = new BloomFilter(total, props.bloomFpp());
        for (String userId : source.allUserIds()) {
            bloom.add(userId); // 全量预热（含无画像长尾）：布隆=池 membership，伪造 ID 不在集合内
        }
        log.info("bloom filter ready: {} entries, bits={}, hashes={}, init {}ms",
                total, bloom.bits(), bloom.hashes(), System.currentTimeMillis() - t0);
        return bloom;
    }

    public static void main(String[] args) {
        SpringApplication.run(FeatureApplication.class, args);
    }
}
