package com.loopers.application.product;

import com.loopers.application.catalog.ProductInfo;
import com.loopers.application.catalog.ProductInfoList;
import com.loopers.cache.CacheKey;
import com.loopers.cache.CacheTemplate;
import com.loopers.cache.SimpleCacheKey;
import com.loopers.domain.product.ProductDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 상품 조회 결과를 캐시하는 서비스.
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
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCacheService {

    private static final String CACHE_KEY_PREFIX_LIST = "product:list:";
    private static final String CACHE_KEY_PREFIX_DETAIL = "product:detail:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(1); // 1분 TTL

    private final CacheTemplate cacheTemplate;
    
    /**
     * 로컬 캐시: 상품별 좋아요 수 델타 (productId -> likeCount delta)
     * <p>
     * 좋아요 추가/취소 시 델타를 저장하고, 캐시 조회 시 델타를 적용하여 반환합니다.
     * 배치 집계 후에는 초기화됩니다.
     * </p>
     */
    private final ConcurrentHashMap<Long, Long> likeCountDeltaCache = new ConcurrentHashMap<>();

    /**
     * 상품 목록 조회 결과를 캐시에서 조회합니다.
     * <p>
     * 페이지 번호와 관계없이 캐시를 확인하고, 캐시에 있으면 반환합니다.
     * 캐시에 없으면 null을 반환하여 DB 조회를 유도합니다.
     * </p>
     * <p>
     * 로컬 캐시의 좋아요 수 델타를 적용하여 반환합니다.
     * </p>
     *
     * @param brandId 브랜드 ID (null이면 전체)
     * @param sort 정렬 기준
     * @param page 페이지 번호
     * @param size 페이지당 상품 수
     * @return 캐시된 상품 목록 (없으면 null)
     */
    public ProductInfoList getCachedProductList(Long brandId, String sort, int page, int size) {
        String cacheKey = buildListCacheKey(brandId, sort, page, size);
        CacheKey<ProductInfoList> key = SimpleCacheKey.of(
            cacheKey,
            CACHE_TTL,
            ProductInfoList.class
        );

        Optional<ProductInfoList> cached = cacheTemplate.get(key);
        if (cached.isEmpty()) {
            return null;
        }

        // 로컬 캐시의 좋아요 수 델타 적용
        return applyLikeCountDelta(cached.get());
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

        String cacheKey = buildListCacheKey(brandId, sort, page, size);
        CacheKey<ProductInfoList> key = SimpleCacheKey.of(
            cacheKey,
            CACHE_TTL,
            ProductInfoList.class
        );

        cacheTemplate.put(key, productInfoList);
    }

    /**
     * 상품 상세 조회 결과를 캐시에서 조회합니다.
     * <p>
     * 로컬 캐시의 좋아요 수 델타를 적용하여 반환합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @return 캐시된 상품 정보 (없으면 null)
     */
    public ProductInfo getCachedProduct(Long productId) {
        String cacheKey = buildDetailCacheKey(productId);
        CacheKey<ProductInfo> key = SimpleCacheKey.of(
            cacheKey,
            CACHE_TTL,
            ProductInfo.class
        );

        Optional<ProductInfo> cached = cacheTemplate.get(key);
        if (cached.isEmpty()) {
            return null;
        }

        // 로컬 캐시의 좋아요 수 델타 적용
        return applyLikeCountDelta(cached.get());
    }

    /**
     * 상품 상세 조회 결과를 캐시에 저장합니다.
     *
     * @param productId 상품 ID
     * @param productInfo 캐시할 상품 정보
     */
    public void cacheProduct(Long productId, ProductInfo productInfo) {
        String cacheKey = buildDetailCacheKey(productId);
        CacheKey<ProductInfo> key = SimpleCacheKey.of(
            cacheKey,
            CACHE_TTL,
            ProductInfo.class
        );

        cacheTemplate.put(key, productInfo);
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

    /**
     * 좋아요 수 델타를 증가시킵니다.
     * <p>
     * 좋아요 추가 시 호출됩니다.
     * </p>
     *
     * @param productId 상품 ID
     */
    public void incrementLikeCountDelta(Long productId) {
        likeCountDeltaCache.merge(productId, 1L, Long::sum);
    }

    /**
     * 좋아요 수 델타를 감소시킵니다.
     * <p>
     * 좋아요 취소 시 호출됩니다.
     * </p>
     *
     * @param productId 상품 ID
     */
    public void decrementLikeCountDelta(Long productId) {
        likeCountDeltaCache.merge(productId, -1L, Long::sum);
    }

    /**
     * 모든 좋아요 수 델타를 초기화합니다.
     * <p>
     * 배치 집계 후 호출됩니다.
     * </p>
     */
    public void clearAllLikeCountDelta() {
        likeCountDeltaCache.clear();
    }

    /**
     * 상품 목록에 좋아요 수 델타를 적용합니다.
     * <p>
     * DB에서 직접 조회한 결과에도 델타를 적용하기 위해 public으로 제공합니다.
     * </p>
     *
     * @param productInfoList 상품 목록
     * @return 델타가 적용된 상품 목록
     */
    public ProductInfoList applyLikeCountDelta(ProductInfoList productInfoList) {
        if (likeCountDeltaCache.isEmpty()) {
            return productInfoList;
        }

        List<ProductInfo> updatedProducts = productInfoList.products().stream()
            .map(this::applyLikeCountDelta)
            .collect(Collectors.toList());

        return new ProductInfoList(
            updatedProducts,
            productInfoList.totalCount(),
            productInfoList.page(),
            productInfoList.size()
        );
    }

    /**
     * 상품 정보에 좋아요 수 델타를 적용합니다.
     * <p>
     * DB에서 직접 조회한 결과에도 델타를 적용하기 위해 public으로 제공합니다.
     * </p>
     *
     * @param productInfo 상품 정보
     * @return 델타가 적용된 상품 정보
     */
    public ProductInfo applyLikeCountDelta(ProductInfo productInfo) {
        Long delta = likeCountDeltaCache.get(productInfo.productDetail().getId());
        if (delta == null || delta == 0) {
            return productInfo;
        }

        ProductDetail originalDetail = productInfo.productDetail();
        Long updatedLikesCount = originalDetail.getLikesCount() + delta;

        ProductDetail updatedDetail = ProductDetail.of(
            originalDetail.getId(),
            originalDetail.getName(),
            originalDetail.getPrice(),
            originalDetail.getStock(),
            originalDetail.getBrandId(),
            originalDetail.getBrandName(),
            updatedLikesCount
        );

        return ProductInfo.withoutRank(updatedDetail);
    }
}

