package com.loopers.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Redis 캐시 템플릿 구현체.
 * <p>
 * Redis를 사용하여 캐시를 구현합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheTemplate implements CacheTemplate {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public <T> Optional<T> get(CacheKey<T> cacheKey) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey.key());
            if (json == null) {
                return Optional.empty();
            }
            T value = deserialize(json, cacheKey.type());
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.warn("캐시 조회 실패. (key: {})", cacheKey.key(), e);
            return Optional.empty();
        }
    }

    @Override
    public <T> void put(CacheKey<T> cacheKey, T value) {
        try {
            String json = serialize(value);
            redisTemplate.opsForValue().set(cacheKey.key(), json, cacheKey.ttl());
        } catch (Exception e) {
            log.warn("캐시 저장 실패. (key: {})", cacheKey.key(), e);
            // 캐시 저장 실패는 무시 (DB 조회로 폴백 가능)
        }
    }

    @Override
    public void evict(CacheKey<?> cacheKey) {
        try {
            redisTemplate.delete(cacheKey.key());
        } catch (Exception e) {
            log.warn("캐시 삭제 실패. (key: {})", cacheKey.key(), e);
        }
    }

    @Override
    public <T> T getOrLoad(CacheKey<T> cacheKey, Supplier<T> loader) {
        Optional<T> cached = get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        T value = loader.get();
        if (value != null) {
            put(cacheKey, value);
        }
        return value;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CacheSerializationException("캐시 직렬화 실패", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new CacheSerializationException("캐시 역직렬화 실패", e);
        }
    }
}

