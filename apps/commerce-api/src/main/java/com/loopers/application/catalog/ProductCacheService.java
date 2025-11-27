package com.loopers.application.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 상품 조회 결과를 Redis에 캐시하는 서비스.
 * <p>
 * 상품 목록 조회와 상품 상세 조회 결과를 캐시하여 성능을 향상시킵니다.
 * </p>
 * <p>
 * <b>캐시 전략:</b>
 * <ul>
 *   <li><b>상품 목록:</b> 첫 3페이지만 캐시하여 메모리 사용량 최적화</li>
 *   <li><b>상품 상세:</b> 모든 상품 상세 정보 캐시</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 */
@Service
@RequiredArgsConstructor
public class ProductCacheService {

    private static final String CACHE_KEY_PREFIX_LIST = "product:list:";
    private static final String CACHE_KEY_PREFIX_DETAIL = "product:detail:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(1); // 1분 TTL

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 상품 목록 조회 결과를 캐시에서 조회합니다.
     * <p>
     * 페이지 번호와 관계없이 캐시를 확인하고, 캐시에 있으면 반환합니다.
     * 캐시에 없으면 null을 반환하여 DB 조회를 유도합니다.
     * </p>
     *
     * @param brandId 브랜드 ID (null이면 전체)
     * @param sort 정렬 기준
     * @param page 페이지 번호
     * @param size 페이지당 상품 수
     * @return 캐시된 상품 목록 (없으면 null)
     */
    public ProductInfoList getCachedProductList(Long brandId, String sort, int page, int size) {
        try {
            String key = buildListCacheKey(brandId, sort, page, size);
            String cachedValue = redisTemplate.opsForValue().get(key);
            
            if (cachedValue == null) {
                return null;
            }
            return objectMapper.readValue(cachedValue, new TypeReference<ProductInfoList>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 상품 목록 조회 결과를 캐시에 저장합니다.
     * <p>
     * 첫 3페이지인 경우에만 캐시에 저장합니다.
     * </p>
     *
     * @param brandId 브랜드 ID (null이면 전체)
     * @param sort 정렬 기준
     * @param page 페이지 번호
     * @param size 페이지당 상품 수
     * @param productInfoList 캐시할 상품 목록
     */
    public void cacheProductList(Long brandId, String sort, int page, int size, ProductInfoList productInfoList) {
        // 3페이지까지만 캐시 저장
        if (page > 2) {
            return;
        }
        
        try {
            String key = buildListCacheKey(brandId, sort, page, size);
            String value = objectMapper.writeValueAsString(productInfoList);
            redisTemplate.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception e) {
            // 캐시 저장 실패는 무시 (DB 조회로 폴백 가능)
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
            // 캐시 저장 실패는 무시 (DB 조회로 폴백 가능)
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
        // sort가 null이면 기본값 "latest" 사용 (컨트롤러와 동일한 기본값)
        String sortValue = sort != null ? sort : "latest";
        return String.format("%s%s:sort:%s:page:%d:size:%d", 
            CACHE_KEY_PREFIX_LIST, brandPart, sortValue, page, size);
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

