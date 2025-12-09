package com.loopers.cache;

import java.time.Duration;

/**
 * 간단한 캐시 키 구현체.
 * <p>
 * 기본적인 캐시 키를 생성할 때 사용합니다.
 * </p>
 *
 * @param <T> 캐시 값의 타입
 * @author Loopers
 * @version 1.0
 */
public record SimpleCacheKey<T>(
    String key,
    Duration ttl,
    Class<T> type
) implements CacheKey<T> {

    /**
     * 캐시 키를 생성합니다.
     *
     * @param key 캐시 키 문자열
     * @param ttl TTL
     * @param type 캐시 값의 타입
     * @param <T> 캐시 값의 타입
     * @return 캐시 키
     */
    public static <T> SimpleCacheKey<T> of(String key, Duration ttl, Class<T> type) {
        return new SimpleCacheKey<>(key, ttl, type);
    }
}

