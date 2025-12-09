package com.loopers.cache;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 캐시 템플릿 인터페이스.
 * <p>
 * 캐시 조회, 저장, 삭제 등의 기능을 제공합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface CacheTemplate {

    /**
     * 캐시에서 값을 조회합니다.
     *
     * @param cacheKey 캐시 키
     * @param <T> 캐시 값의 타입
     * @return 캐시 값 (Optional)
     */
    <T> Optional<T> get(CacheKey<T> cacheKey);

    /**
     * 캐시에 값을 저장합니다.
     *
     * @param cacheKey 캐시 키
     * @param value 저장할 값
     * @param <T> 캐시 값의 타입
     */
    <T> void put(CacheKey<T> cacheKey, T value);

    /**
     * 캐시를 무효화합니다.
     *
     * @param cacheKey 캐시 키
     */
    void evict(CacheKey<?> cacheKey);

    /**
     * 캐시에서 값을 조회하고, 없으면 로더를 실행하여 값을 가져온 후 캐시에 저장합니다.
     * <p>
     * Cache-Aside 패턴을 구현합니다.
     * </p>
     *
     * @param cacheKey 캐시 키
     * @param loader 캐시에 값이 없을 때 실행할 로더
     * @param <T> 캐시 값의 타입
     * @return 캐시 값 또는 로더로부터 가져온 값
     */
    <T> T getOrLoad(CacheKey<T> cacheKey, Supplier<T> loader);
}

