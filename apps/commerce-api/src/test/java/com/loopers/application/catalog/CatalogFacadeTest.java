package com.loopers.application.catalog;

import com.loopers.application.brand.BrandService;
import com.loopers.application.product.ProductCacheService;
import com.loopers.application.product.ProductService;
import com.loopers.application.ranking.RankingService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductDetail;
import com.loopers.domain.product.ProductEvent;
import com.loopers.domain.product.ProductEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CatalogFacade 테스트.
 * <p>
 * 상품 조회 시 랭킹 정보가 포함되는지 검증합니다.
 * 캐시 히트/미스의 세부 로직은 ProductCacheService 테스트에서 검증합니다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogFacade 상품 조회 랭킹 정보 포함 테스트")
class CatalogFacadeTest {

    @Mock
    private BrandService brandService;

    @Mock
    private ProductService productService;

    @Mock
    private ProductCacheService productCacheService;

    @Mock
    private ProductEventPublisher productEventPublisher;

    @Mock
    private RankingService rankingService;

    @InjectMocks
    private CatalogFacade catalogFacade;

    private static final Long PRODUCT_ID = 1L;
    private static final Long BRAND_ID = 10L;
    private static final String BRAND_NAME = "테스트 브랜드";
    private static final String PRODUCT_NAME = "테스트 상품";
    private static final Integer PRODUCT_PRICE = 10000;
    private static final Integer PRODUCT_STOCK = 10;
    private static final Long LIKES_COUNT = 5L;

    /**
     * Product에 ID를 설정합니다 (리플렉션 사용).
     */
    private void setId(Product product, Long id) {
        try {
            Field idField = product.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(product, id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set Product ID", e);
        }
    }

    /**
     * Brand에 ID를 설정합니다 (리플렉션 사용).
     */
    private void setId(Brand brand, Long id) {
        try {
            Field idField = brand.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(brand, id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set Brand ID", e);
        }
    }

    @Test
    @DisplayName("캐시 히트 시 랭킹 정보가 포함된다")
    void getProduct_withCacheHit_includesRanking() {
        // arrange
        ProductDetail cachedProductDetail = ProductDetail.of(
            PRODUCT_ID,
            PRODUCT_NAME,
            PRODUCT_PRICE,
            PRODUCT_STOCK,
            BRAND_ID,
            BRAND_NAME,
            LIKES_COUNT
        );
        ProductInfo cachedProductInfo = ProductInfo.withoutRank(cachedProductDetail);
        Long expectedRank = 3L;

        when(productCacheService.getCachedProduct(PRODUCT_ID))
            .thenReturn(cachedProductInfo);
        when(rankingService.getProductRank(eq(PRODUCT_ID), any(LocalDate.class)))
            .thenReturn(expectedRank);

        // act
        ProductInfo result = catalogFacade.getProduct(PRODUCT_ID);

        // assert
        assertThat(result.rank()).isEqualTo(expectedRank);
        verify(rankingService).getProductRank(eq(PRODUCT_ID), any(LocalDate.class));
        verify(productEventPublisher).publish(any(ProductEvent.ProductViewed.class));
        verify(productService, never()).getProduct(any());
    }

    @Test
    @DisplayName("캐시 히트 시 랭킹에 없는 상품은 null을 반환한다")
    void getProduct_withCacheHit_noRanking_returnsNull() {
        // arrange
        ProductDetail cachedProductDetail = ProductDetail.of(
            PRODUCT_ID,
            PRODUCT_NAME,
            PRODUCT_PRICE,
            PRODUCT_STOCK,
            BRAND_ID,
            BRAND_NAME,
            LIKES_COUNT
        );
        ProductInfo cachedProductInfo = ProductInfo.withoutRank(cachedProductDetail);

        when(productCacheService.getCachedProduct(PRODUCT_ID))
            .thenReturn(cachedProductInfo);
        when(rankingService.getProductRank(eq(PRODUCT_ID), any(LocalDate.class)))
            .thenReturn(null);

        // act
        ProductInfo result = catalogFacade.getProduct(PRODUCT_ID);

        // assert
        assertThat(result.rank()).isNull();
        verify(rankingService).getProductRank(eq(PRODUCT_ID), any(LocalDate.class));
    }

    @Test
    @DisplayName("캐시 미스 시 랭킹 정보가 포함된다")
    void getProduct_withCacheMiss_includesRanking() {
        // arrange
        Product product = Product.of(PRODUCT_NAME, PRODUCT_PRICE, PRODUCT_STOCK, BRAND_ID);
        setId(product, PRODUCT_ID);
        
        // Product.likeCount 설정 (리플렉션 사용)
        try {
            Field likeCountField = Product.class.getDeclaredField("likeCount");
            likeCountField.setAccessible(true);
            likeCountField.set(product, LIKES_COUNT);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set Product likeCount", e);
        }

        Brand brand = Brand.of(BRAND_NAME);
        setId(brand, BRAND_ID);

        Long expectedRank = 5L;

        when(productCacheService.getCachedProduct(PRODUCT_ID))
            .thenReturn(null);
        when(productService.getProduct(PRODUCT_ID))
            .thenReturn(product);
        when(brandService.getBrand(BRAND_ID))
            .thenReturn(brand);
        when(rankingService.getProductRank(eq(PRODUCT_ID), any(LocalDate.class)))
            .thenReturn(expectedRank);
        when(productCacheService.applyLikeCountDelta(any(ProductInfo.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        ProductInfo result = catalogFacade.getProduct(PRODUCT_ID);

        // assert
        assertThat(result.rank()).isEqualTo(expectedRank);
        verify(rankingService).getProductRank(eq(PRODUCT_ID), any(LocalDate.class));
        verify(productEventPublisher).publish(any(ProductEvent.ProductViewed.class));
        verify(productService).getProduct(PRODUCT_ID);
        verify(productCacheService).cacheProduct(eq(PRODUCT_ID), any(ProductInfo.class));
    }

    @Test
    @DisplayName("캐시 미스 시 랭킹에 없는 상품은 null을 반환한다")
    void getProduct_withCacheMiss_noRanking_returnsNull() {
        // arrange
        Product product = Product.of(PRODUCT_NAME, PRODUCT_PRICE, PRODUCT_STOCK, BRAND_ID);
        setId(product, PRODUCT_ID);
        
        // Product.likeCount 설정 (리플렉션 사용)
        try {
            Field likeCountField = Product.class.getDeclaredField("likeCount");
            likeCountField.setAccessible(true);
            likeCountField.set(product, LIKES_COUNT);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set Product likeCount", e);
        }

        Brand brand = Brand.of(BRAND_NAME);
        setId(brand, BRAND_ID);

        when(productCacheService.getCachedProduct(PRODUCT_ID))
            .thenReturn(null);
        when(productService.getProduct(PRODUCT_ID))
            .thenReturn(product);
        when(brandService.getBrand(BRAND_ID))
            .thenReturn(brand);
        when(rankingService.getProductRank(eq(PRODUCT_ID), any(LocalDate.class)))
            .thenReturn(null);
        when(productCacheService.applyLikeCountDelta(any(ProductInfo.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        ProductInfo result = catalogFacade.getProduct(PRODUCT_ID);

        // assert
        assertThat(result.rank()).isNull();
        verify(rankingService).getProductRank(eq(PRODUCT_ID), any(LocalDate.class));
    }
}

