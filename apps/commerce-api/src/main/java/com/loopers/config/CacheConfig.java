package com.loopers.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Caffeine 기반 CacheManager 설정.
     * <p>
     * - userInfo 캐시: userId 기준 사용자 정보 캐싱<br>
     * - TTL: 5분<br>
     * - 최대 캐시 엔트리 수: 10_000
     * </p>
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("userInfo");
        cacheManager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(10_000)
                // Micrometer를 통한 Prometheus 노출을 위해 통계 수집 활성화
                .recordStats()
        );
        return cacheManager;
    }
}


