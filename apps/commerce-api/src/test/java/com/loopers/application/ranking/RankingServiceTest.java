package com.loopers.application.ranking;

import com.loopers.application.brand.BrandService;
import com.loopers.application.product.ProductService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.zset.RedisZSetTemplate;
import com.loopers.zset.ZSetEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RankingService 테스트.
 */
@ExtendWith(MockitoExtension.class)
class RankingServiceTest {

    @Mock
    private RedisZSetTemplate zSetTemplate;

    @Mock
    private RankingKeyGenerator keyGenerator;

    @Mock
    private ProductService productService;

    @Mock
    private BrandService brandService;

    @InjectMocks
    private RankingService rankingService;

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

    @DisplayName("랭킹을 조회할 수 있다.")
    @Test
    void canGetRankings() {
        // arrange
        LocalDate date = LocalDate.of(2024, 12, 15);
        int page = 0;
        int size = 20;
        String key = "ranking:all:20241215";

        Long productId1 = 1L;
        Long productId2 = 2L;
        Long brandId1 = 10L;
        Long brandId2 = 20L;

        List<ZSetEntry> entries = List.of(
            new ZSetEntry(String.valueOf(productId1), 100.5),
            new ZSetEntry(String.valueOf(productId2), 90.3)
        );

        Product product1 = Product.of("상품1", 10000, 10, brandId1);
        Product product2 = Product.of("상품2", 20000, 5, brandId2);
        Brand brand1 = Brand.of("브랜드1");
        Brand brand2 = Brand.of("브랜드2");

        // ID 설정
        setId(product1, productId1);
        setId(product2, productId2);
        setId(brand1, brandId1);
        setId(brand2, brandId2);
        when(keyGenerator.generateDailyKey(date)).thenReturn(key);
        when(zSetTemplate.getTopRankings(key, 0L, 19L)).thenReturn(entries);
        when(zSetTemplate.getSize(key)).thenReturn(50L);
        when(productService.getProducts(List.of(productId1, productId2)))
            .thenReturn(List.of(product1, product2));
        when(brandService.getBrands(List.of(brandId1, brandId2)))
            .thenReturn(List.of(brand1, brand2));

        // act
        RankingService.RankingsResponse result = rankingService.getRankings(date, page, size);

        // assert
        assertThat(result.items()).hasSize(2);
        assertThat(result.page()).isEqualTo(page);
        assertThat(result.size()).isEqualTo(size);
        assertThat(result.hasNext()).isTrue();

        RankingService.RankingItem item1 = result.items().get(0);
        assertThat(item1.rank()).isEqualTo(1L);
        assertThat(item1.score()).isEqualTo(100.5);
        assertThat(item1.productDetail().getId()).isEqualTo(productId1);
        assertThat(item1.productDetail().getName()).isEqualTo("상품1");

        RankingService.RankingItem item2 = result.items().get(1);
        assertThat(item2.rank()).isEqualTo(2L);
        assertThat(item2.score()).isEqualTo(90.3);
        assertThat(item2.productDetail().getId()).isEqualTo(productId2);
        assertThat(item2.productDetail().getName()).isEqualTo("상품2");
    }

    @DisplayName("빈 랭킹을 조회할 수 있다.")
    @Test
    void canGetEmptyRankings() {
        // arrange
        LocalDate date = LocalDate.of(2024, 12, 15);
        int page = 0;
        int size = 20;
        String key = "ranking:all:20241215";

        when(keyGenerator.generateDailyKey(date)).thenReturn(key);
        when(zSetTemplate.getTopRankings(key, 0L, 19L)).thenReturn(List.of());

        // act
        RankingService.RankingsResponse result = rankingService.getRankings(date, page, size);

        // assert
        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(page);
        assertThat(result.size()).isEqualTo(size);
        assertThat(result.hasNext()).isFalse();
        verify(zSetTemplate, never()).getSize(anyString());
    }

    @DisplayName("페이징이 정상적으로 동작한다.")
    @Test
    void canGetRankingsWithPaging() {
        // arrange
        LocalDate date = LocalDate.of(2024, 12, 15);
        int page = 2;
        int size = 10;
        String key = "ranking:all:20241215";

        Long productId = 1L;
        Long brandId = 10L;

        List<ZSetEntry> entries = List.of(
            new ZSetEntry(String.valueOf(productId), 100.0)
        );

        Product product = Product.of("상품", 10000, 10, brandId);
        Brand brand = Brand.of("브랜드");

        // ID 설정
        setId(product, productId);
        setId(brand, brandId);

        when(keyGenerator.generateDailyKey(date)).thenReturn(key);
        when(zSetTemplate.getTopRankings(key, 20L, 29L)).thenReturn(entries);
        when(zSetTemplate.getSize(key)).thenReturn(31L); // 31 > 20 + 10이므로 다음 페이지 있음
        when(productService.getProducts(List.of(productId))).thenReturn(List.of(product));
        when(brandService.getBrands(List.of(brandId))).thenReturn(List.of(brand));

        // act
        RankingService.RankingsResponse result = rankingService.getRankings(date, page, size);

        // assert
        assertThat(result.items()).hasSize(1);
        assertThat(result.page()).isEqualTo(page);
        assertThat(result.size()).isEqualTo(size);
        assertThat(result.hasNext()).isTrue(); // 31 > 20 + 10

        RankingService.RankingItem item = result.items().get(0);
        assertThat(item.rank()).isEqualTo(21L); // start(20) + i(0) + 1
    }

    @DisplayName("랭킹에 포함된 상품이 DB에 없으면 스킵한다.")
    @Test
    void skipsProduct_whenProductNotFound() {
        // arrange
        LocalDate date = LocalDate.of(2024, 12, 15);
        int page = 0;
        int size = 20;
        String key = "ranking:all:20241215";

        Long productId1 = 1L;
        Long productId2 = 999L; // 존재하지 않는 상품

        List<ZSetEntry> entries = List.of(
            new ZSetEntry(String.valueOf(productId1), 100.0),
            new ZSetEntry(String.valueOf(productId2), 90.0)
        );

        Product product1 = Product.of("상품1", 10000, 10, 10L);
        Brand brand1 = Brand.of("브랜드1");

        // ID 설정
        setId(product1, productId1);
        setId(brand1, 10L);

        when(keyGenerator.generateDailyKey(date)).thenReturn(key);
        when(zSetTemplate.getTopRankings(key, 0L, 19L)).thenReturn(entries);
        when(zSetTemplate.getSize(key)).thenReturn(2L);
        when(productService.getProducts(List.of(productId1, productId2)))
            .thenReturn(List.of(product1)); // productId2는 없음
        when(brandService.getBrands(List.of(10L))).thenReturn(List.of(brand1));

        // act
        RankingService.RankingsResponse result = rankingService.getRankings(date, page, size);

        // assert
        assertThat(result.items()).hasSize(1); // productId2는 스킵됨
        assertThat(result.items().get(0).productDetail().getId()).isEqualTo(productId1);
    }

    @DisplayName("상품의 브랜드가 없으면 스킵한다.")
    @Test
    void skipsProduct_whenBrandNotFound() {
        // arrange
        LocalDate date = LocalDate.of(2024, 12, 15);
        int page = 0;
        int size = 20;
        String key = "ranking:all:20241215";

        Long productId1 = 1L;
        Long productId2 = 2L;
        Long brandId1 = 10L;
        Long brandId2 = 999L; // 존재하지 않는 브랜드

        List<ZSetEntry> entries = List.of(
            new ZSetEntry(String.valueOf(productId1), 100.0),
            new ZSetEntry(String.valueOf(productId2), 90.0)
        );

        Product product1 = Product.of("상품1", 10000, 10, brandId1);
        Product product2 = Product.of("상품2", 20000, 5, brandId2);
        Brand brand1 = Brand.of("브랜드1");

        // ID 설정
        setId(product1, productId1);
        setId(product2, productId2);
        setId(brand1, brandId1);

        when(keyGenerator.generateDailyKey(date)).thenReturn(key);
        when(zSetTemplate.getTopRankings(key, 0L, 19L)).thenReturn(entries);
        when(zSetTemplate.getSize(key)).thenReturn(2L);
        when(productService.getProducts(List.of(productId1, productId2)))
            .thenReturn(List.of(product1, product2));
        when(brandService.getBrands(List.of(brandId1, brandId2)))
            .thenReturn(List.of(brand1)); // brandId2는 없음

        // act
        RankingService.RankingsResponse result = rankingService.getRankings(date, page, size);

        // assert
        assertThat(result.items()).hasSize(1); // productId2는 브랜드가 없어서 스킵됨
        assertThat(result.items().get(0).productDetail().getId()).isEqualTo(productId1);
    }

    @DisplayName("다음 페이지가 없을 때 hasNext가 false이다.")
    @Test
    void hasNextIsFalse_whenNoMorePages() {
        // arrange
        LocalDate date = LocalDate.of(2024, 12, 15);
        int page = 0;
        int size = 20;
        String key = "ranking:all:20241215";

        Long productId = 1L;
        Long brandId = 10L;

        List<ZSetEntry> entries = List.of(
            new ZSetEntry(String.valueOf(productId), 100.0)
        );

        Product product = Product.of("상품", 10000, 10, brandId);
        Brand brand = Brand.of("브랜드");

        // ID 설정
        setId(product, productId);
        setId(brand, brandId);

        when(keyGenerator.generateDailyKey(date)).thenReturn(key);
        when(zSetTemplate.getTopRankings(key, 0L, 19L)).thenReturn(entries);
        when(zSetTemplate.getSize(key)).thenReturn(1L); // 전체 크기가 1이므로 다음 페이지 없음
        when(productService.getProducts(List.of(productId))).thenReturn(List.of(product));
        when(brandService.getBrands(List.of(brandId))).thenReturn(List.of(brand));

        // act
        RankingService.RankingsResponse result = rankingService.getRankings(date, page, size);

        // assert
        assertThat(result.hasNext()).isFalse(); // 1 <= 0 + 20
    }

    @DisplayName("특정 상품의 순위를 조회할 수 있다.")
    @Test
    void canGetProductRank() {
        // arrange
        Long productId = 1L;
        LocalDate date = LocalDate.of(2024, 12, 15);
        String key = "ranking:all:20241215";
        Long rank = 5L; // 0-based

        when(keyGenerator.generateDailyKey(date)).thenReturn(key);
        when(zSetTemplate.getRank(key, String.valueOf(productId))).thenReturn(rank);

        // act
        Long result = rankingService.getProductRank(productId, date);

        // assert
        assertThat(result).isEqualTo(6L); // 1-based (5 + 1)
        verify(keyGenerator).generateDailyKey(date);
        verify(zSetTemplate).getRank(key, String.valueOf(productId));
    }

    @DisplayName("랭킹에 없는 상품의 순위는 null이다.")
    @Test
    void returnsNull_whenProductNotInRanking() {
        // arrange
        Long productId = 999L;
        LocalDate date = LocalDate.of(2024, 12, 15);
        String key = "ranking:all:20241215";

        when(keyGenerator.generateDailyKey(date)).thenReturn(key);
        when(zSetTemplate.getRank(key, String.valueOf(productId))).thenReturn(null);

        // act
        Long result = rankingService.getProductRank(productId, date);

        // assert
        assertThat(result).isNull();
        verify(keyGenerator).generateDailyKey(date);
        verify(zSetTemplate).getRank(key, String.valueOf(productId));
    }

    @DisplayName("같은 브랜드의 여러 상품이 랭킹에 포함될 수 있다.")
    @Test
    void canHandleMultipleProductsFromSameBrand() {
        // arrange
        LocalDate date = LocalDate.of(2024, 12, 15);
        int page = 0;
        int size = 20;
        String key = "ranking:all:20241215";

        Long productId1 = 1L;
        Long productId2 = 2L;
        Long brandId = 10L; // 같은 브랜드

        List<ZSetEntry> entries = List.of(
            new ZSetEntry(String.valueOf(productId1), 100.0),
            new ZSetEntry(String.valueOf(productId2), 90.0)
        );

        Product product1 = Product.of("상품1", 10000, 10, brandId);
        Product product2 = Product.of("상품2", 20000, 5, brandId);
        Brand brand = Brand.of("브랜드");

        // ID 설정
        setId(product1, productId1);
        setId(product2, productId2);
        setId(brand, brandId);

        when(keyGenerator.generateDailyKey(date)).thenReturn(key);
        when(zSetTemplate.getTopRankings(key, 0L, 19L)).thenReturn(entries);
        when(zSetTemplate.getSize(key)).thenReturn(2L);
        when(productService.getProducts(List.of(productId1, productId2)))
            .thenReturn(List.of(product1, product2));
        when(brandService.getBrands(List.of(brandId))) // 중복 제거되어 한 번만 조회
            .thenReturn(List.of(brand));

        // act
        RankingService.RankingsResponse result = rankingService.getRankings(date, page, size);

        // assert
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).productDetail().getBrandId()).isEqualTo(brandId);
        assertThat(result.items().get(1).productDetail().getBrandId()).isEqualTo(brandId);
        // 브랜드는 한 번만 조회됨 (중복 제거)
        verify(brandService).getBrands(List.of(brandId));
    }
}
