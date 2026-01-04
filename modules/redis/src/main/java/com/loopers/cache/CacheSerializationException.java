package com.loopers.cache;

/**
 * 캐시 직렬화/역직렬화 예외.
 *
 * @author Loopers
 * @version 1.0
 */
public class CacheSerializationException extends RuntimeException {

    public CacheSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}

