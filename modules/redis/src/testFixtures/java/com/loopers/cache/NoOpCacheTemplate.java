package com.loopers.cache;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 테스트용 No-Op 캐시 템플릿.
 * <p>
 * 테스트에서 캐시를 사용하지 않을 때 사용합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public class NoOpCacheTemplate implements CacheTemplate {

    @Override
    public <T> Optional<T> get(CacheKey<T> cacheKey) {
        return Optional.empty();
    }

    @Override
    public <T> void put(CacheKey<T> cacheKey, T value) {
        // No-op
    }

    @Override
    public void evict(CacheKey<?> cacheKey) {
        // No-op
    }

    @Override
    public <T> T getOrLoad(CacheKey<T> cacheKey, Supplier<T> loader) {
        return loader.get();
    }
}

