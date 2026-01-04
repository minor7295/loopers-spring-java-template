package com.loopers.cache;

import java.time.Duration;

/**
 * 캐시 키 인터페이스.
 * <p>
 * 캐시 키는 해당 인터페이스를 기반으로 구현되어야 합니다.
 * </p>
 *
 * @param <T> 캐시 값의 타입
 * @author Loopers
 * @version 1.0
 */
public interface CacheKey<T> {

    /**
     * 캐시 키를 반환합니다.
     *
     * @return 캐시 키 문자열
     */
    String key();

    /**
     * 캐시 TTL (Time To Live)을 반환합니다.
     *
     * @return TTL
     */
    Duration ttl();

    /**
     * 캐시 값의 타입을 반환합니다.
     * <p>
     * 역직렬화 시 사용됩니다.
     * </p>
     *
     * @return 타입
     */
    Class<T> type();
}

