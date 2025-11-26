package com.loopers.config;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine 기반 로컬 캐시 메트릭을 Micrometer/Prometheus로 노출하기 위한 수동 바인딩 설정.
 *
 * - 캐시별 엔트리 수(caffeine_cache_size)
 * - 캐시별 누적 히트/미스(caffeine_cache_hits_total, caffeine_cache_misses_total)
 */
@Configuration
public class CacheMetricsConfig {

    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;

    public CacheMetricsConfig(CacheManager cacheManager, MeterRegistry meterRegistry) {
        this.cacheManager = cacheManager;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void bindCaffeineCachesToMetrics() {
        if (!(cacheManager instanceof CaffeineCacheManager caffeineCacheManager)) {
            return;
        }

        for (String cacheName : caffeineCacheManager.getCacheNames()) {
            org.springframework.cache.Cache springCache = caffeineCacheManager.getCache(cacheName);
            if (!(springCache instanceof CaffeineCache caffeineCache)) {
                continue;
            }

            Cache<Object, Object> nativeCache = caffeineCache.getNativeCache();
            Tags tags = Tags.of("cache", cacheName);

            // 캐시 엔트리 수
            Gauge.builder("caffeine_cache_size", nativeCache, c -> c.estimatedSize())
                .tags(tags)
                .register(meterRegistry);

            // 누적 히트 수
            FunctionCounter.builder("caffeine_cache_hits_total", nativeCache, c -> c.stats().hitCount())
                .tags(tags)
                .register(meterRegistry);

            // 누적 미스 수
            FunctionCounter.builder("caffeine_cache_misses_total", nativeCache, c -> c.stats().missCount())
                .tags(tags)
                .register(meterRegistry);
        }
    }
}


