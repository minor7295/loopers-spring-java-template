package com.loopers.application.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;

/**
 * 상품 조회 결과를 Redis에 캐시하는 서비스.
 * <p>
 * 상품 목록 조회와 상품 상세 조회 결과를 캐시하여 성능을 향상시킵니다.
 * </p>
 * <p>
 * <b>캐시 전략:</b>
 * <ul>
 *   <li><b>상품 목록:</b> 첫 페이지(page=0)만 캐시하여 메모리 사용량 최적화</li>
 *   <li><b>상품 상세:</b> 모든 상품 상세 정보 캐시</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 */
@Slf4j
@Service
public class ProductCacheService {

    private static final String CACHE_KEY_PREFIX_LIST = "product:list:";
    private static final String CACHE_KEY_PREFIX_DETAIL = "product:detail:";
    private static final String CACHE_KEY_PATTERN_LIST = "product:list:*";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5); // 5분 TTL

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> evictByPatternScript;

    /**
     * Lua 스크립트를 사용한 캐시 무효화를 위한 생성자.
     * <p>
     * SCAN 기반 패턴 매칭으로 KEYS 명령어의 블로킹 문제를 해결합니다.
     * </p>
     */
    public ProductCacheService(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        
        // Lua 스크립트 로드
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/evict-by-pattern.lua")));
        script.setResultType(Long.class);
        this.evictByPatternScript = script;
    }

    /**
     * 상품 목록 조회 결과를 캐시에서 조회합니다.
     * <p>
     * 첫 페이지(page=0)인 경우에만 캐시에서 조회합니다.
     * </p>
     *
     * @param brandId 브랜드 ID (null이면 전체)
     * @param sort 정렬 기준
     * @param page 페이지 번호
     * @param size 페이지당 상품 수
     * @return 캐시된 상품 목록 (없거나 첫 페이지가 아니면 null)
     */
    public ProductInfoList getCachedProductList(Long brandId, String sort, int page, int size) {
        // 첫 페이지가 아니면 캐시 조회하지 않음
        if (page != 0) {
            return null;
        }
        
        try {
            String key = buildListCacheKey(brandId, sort, page, size);
            String cachedValue = redisTemplate.opsForValue().get(key);
            
            if (cachedValue == null) {
                return null;
            }
            
            return objectMapper.readValue(cachedValue, new TypeReference<ProductInfoList>() {});
        } catch (Exception e) {
            log.warn("상품 목록 캐시 조회 실패: brandId={}, sort={}, page={}, size={}", 
                brandId, sort, page, size, e);
            return null;
        }
    }

    /**
     * 상품 목록 조회 결과를 캐시에 저장합니다.
     * <p>
     * 첫 페이지(page=0)인 경우에만 캐시에 저장합니다.
     * </p>
     *
     * @param brandId 브랜드 ID (null이면 전체)
     * @param sort 정렬 기준
     * @param page 페이지 번호
     * @param size 페이지당 상품 수
     * @param productInfoList 캐시할 상품 목록
     */
    public void cacheProductList(Long brandId, String sort, int page, int size, ProductInfoList productInfoList) {
        // 첫 페이지가 아니면 캐시 저장하지 않음
        if (page != 0) {
            return;
        }
        
        try {
            String key = buildListCacheKey(brandId, sort, page, size);
            String value = objectMapper.writeValueAsString(productInfoList);
            redisTemplate.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception e) {
            log.warn("상품 목록 캐시 저장 실패: brandId={}, sort={}, page={}, size={}", 
                brandId, sort, page, size, e);
        }
    }

    /**
     * 상품 상세 조회 결과를 캐시에서 조회합니다.
     *
     * @param productId 상품 ID
     * @return 캐시된 상품 정보 (없으면 null)
     */
    public ProductInfo getCachedProduct(Long productId) {
        try {
            String key = buildDetailCacheKey(productId);
            String cachedValue = redisTemplate.opsForValue().get(key);
            
            if (cachedValue == null) {
                return null;
            }
            
            return objectMapper.readValue(cachedValue, new TypeReference<ProductInfo>() {});
        } catch (Exception e) {
            log.warn("상품 상세 캐시 조회 실패: productId={}", productId, e);
            return null;
        }
    }

    /**
     * 상품 상세 조회 결과를 캐시에 저장합니다.
     *
     * @param productId 상품 ID
     * @param productInfo 캐시할 상품 정보
     */
    public void cacheProduct(Long productId, ProductInfo productInfo) {
        try {
            String key = buildDetailCacheKey(productId);
            String value = objectMapper.writeValueAsString(productInfo);
            redisTemplate.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception e) {
            log.warn("상품 상세 캐시 저장 실패: productId={}", productId, e);
        }
    }

    /**
     * 특정 상품의 캐시를 무효화합니다.
     *
     * @param productId 상품 ID
     */
    public void evictProductCache(Long productId) {
        try {
            // 상품 상세 캐시 삭제
            String detailKey = buildDetailCacheKey(productId);
            redisTemplate.delete(detailKey);
            
            // 해당 상품이 포함된 모든 목록 캐시 삭제
            // (실제로는 브랜드별로만 삭제하는 것이 효율적이지만, 간단하게 전체 삭제)
            evictAllProductListCache();
            
            log.debug("상품 캐시 무효화 완료: productId={}", productId);
        } catch (Exception e) {
            log.warn("상품 캐시 무효화 실패: productId={}", productId, e);
        }
    }

    /**
     * 특정 브랜드의 상품 목록 캐시를 무효화합니다.
     * <p>
     * Lua 스크립트를 사용하여 SCAN 기반으로 패턴 매칭 및 삭제를 수행합니다.
     * KEYS 명령어 대신 SCAN을 사용하여 Redis 블로킹을 방지합니다.
     * </p>
     *
     * @param brandId 브랜드 ID
     */
    public void evictProductListCacheByBrand(Long brandId) {
        try {
            String pattern = CACHE_KEY_PREFIX_LIST + "brand:" + brandId + ":*";
            Long deletedCount = redisTemplate.execute(
                evictByPatternScript,
                Collections.emptyList(),
                pattern
            );
            
            if (deletedCount != null && deletedCount > 0) {
                log.debug("브랜드별 상품 목록 캐시 무효화 완료: brandId={}, count={}", brandId, deletedCount);
            }
        } catch (Exception e) {
            log.warn("브랜드별 상품 목록 캐시 무효화 실패: brandId={}", brandId, e);
        }
    }

    /**
     * 모든 상품 목록 캐시를 무효화합니다.
     * <p>
     * Lua 스크립트를 사용하여 SCAN 기반으로 패턴 매칭 및 삭제를 수행합니다.
     * KEYS 명령어 대신 SCAN을 사용하여 Redis 블로킹을 방지합니다.
     * </p>
     */
    public void evictAllProductListCache() {
        try {
            Long deletedCount = redisTemplate.execute(
                evictByPatternScript,
                Collections.emptyList(),
                CACHE_KEY_PATTERN_LIST
            );
            
            if (deletedCount != null && deletedCount > 0) {
                log.debug("모든 상품 목록 캐시 무효화 완료: count={}", deletedCount);
            }
        } catch (Exception e) {
            log.warn("상품 목록 캐시 무효화 실패", e);
        }
    }

    /**
     * 상품 목록 캐시 키를 생성합니다.
     *
     * @param brandId 브랜드 ID (null이면 "all")
     * @param sort 정렬 기준
     * @param page 페이지 번호
     * @param size 페이지당 상품 수
     * @return 캐시 키
     */
    private String buildListCacheKey(Long brandId, String sort, int page, int size) {
        String brandPart = brandId != null ? "brand:" + brandId : "brand:all";
        return String.format("%s%s:sort:%s:page:%d:size:%d", 
            CACHE_KEY_PREFIX_LIST, brandPart, sort, page, size);
    }

    /**
     * 상품 상세 캐시 키를 생성합니다.
     *
     * @param productId 상품 ID
     * @return 캐시 키
     */
    private String buildDetailCacheKey(Long productId) {
        return CACHE_KEY_PREFIX_DETAIL + productId;
    }
}

