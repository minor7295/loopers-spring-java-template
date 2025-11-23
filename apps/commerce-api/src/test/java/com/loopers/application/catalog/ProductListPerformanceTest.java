package com.loopers.application.catalog;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상품 목록 조회 성능 테스트.
 * <p>
 * EXPLAIN 분석을 통해 인덱스 사용 여부를 확인하고,
 * 성능 개선 전후를 비교합니다.
 * </p>
 *
 * @author Loopers
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("상품 목록 조회 성능 테스트")
class ProductListPerformanceTest {

    @Autowired
    private CatalogProductFacade catalogProductFacade;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    private Long brandId1;
    private Long brandId2;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 생성: 브랜드 2개, 각 브랜드당 상품 1000개
        Brand brand1 = Brand.of("브랜드1");
        Brand brand2 = Brand.of("브랜드2");
        brandId1 = brandRepository.save(brand1).getId();
        brandId2 = brandRepository.save(brand2).getId();

        List<Product> products = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            // 브랜드1 상품: 좋아요 수 0~999
            products.add(Product.of("상품1-" + i, 10000 + i, 100, brandId1));
            // 브랜드2 상품: 좋아요 수 0~999
            products.add(Product.of("상품2-" + i, 20000 + i, 100, brandId2));
        }
        productJpaRepository.saveAll(products);

        // 좋아요 수 업데이트 (배치 동기화 시뮬레이션)
        List<Product> allProducts = productRepository.findAll(null, "latest", 0, 2000);
        for (int i = 0; i < allProducts.size(); i++) {
            Product product = allProducts.get(i);
            product.updateLikeCount((long) (i % 1000)); // 0~999 좋아요 수
            productRepository.save(product);
        }
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("브랜드 필터 + 좋아요 순 정렬: EXPLAIN 분석 및 성능 측정")
    void testBrandFilterWithLikesSort_explainAndPerformance() {
        // Given
        String sort = "likes_desc";
        int page = 0;
        int size = 20;

        // When: EXPLAIN 분석
        String explainQuery = """
            EXPLAIN SELECT p.* FROM product p 
            WHERE p.ref_brand_id = :brandId 
            ORDER BY p.like_count DESC 
            LIMIT :size OFFSET :offset
            """;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> explainResult = entityManager.createNativeQuery(explainQuery)
            .setParameter("brandId", brandId1)
            .setParameter("size", size)
            .setParameter("offset", page * size)
            .getResultList();

        // Then: 인덱스 사용 확인
        Map<String, Object> explainRow = explainResult.get(0);
        String key = (String) explainRow.get("key");
        String type = (String) explainRow.get("type");
        
        System.out.println("=== 브랜드 필터 + 좋아요 순 정렬 EXPLAIN 결과 ===");
        explainResult.forEach(row -> {
            System.out.println("key: " + row.get("key"));
            System.out.println("type: " + row.get("type"));
            System.out.println("rows: " + row.get("rows"));
            System.out.println("Extra: " + row.get("Extra"));
            System.out.println("---");
        });

        // 인덱스 사용 확인 (idx_product_brand_likes 또는 idx_product_brand_likes 사용)
        assertThat(key).isNotNull();
        assertThat(key).contains("idx_product_brand_likes");
        assertThat(type).isEqualTo("ref"); // 인덱스를 사용한 조회

        // 성능 측정
        long startTime = System.currentTimeMillis();
        ProductInfoList result = catalogProductFacade.getProducts(brandId1, sort, page, size);
        long endTime = System.currentTimeMillis();
        long executionTime = endTime - startTime;

        System.out.println("실행 시간: " + executionTime + "ms");
        System.out.println("조회된 상품 수: " + result.products().size());

        assertThat(result.products()).hasSize(size);
        assertThat(executionTime).isLessThan(100); // 100ms 이하로 실행되어야 함
    }

    @Test
    @DisplayName("전체 조회 + 좋아요 순 정렬: EXPLAIN 분석 및 성능 측정")
    void testAllProductsWithLikesSort_explainAndPerformance() {
        // Given
        String sort = "likes_desc";
        int page = 0;
        int size = 20;

        // When: EXPLAIN 분석
        String explainQuery = """
            EXPLAIN SELECT p.* FROM product p 
            ORDER BY p.like_count DESC 
            LIMIT :size OFFSET :offset
            """;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> explainResult = entityManager.createNativeQuery(explainQuery)
            .setParameter("size", size)
            .setParameter("offset", page * size)
            .getResultList();

        // Then: 인덱스 사용 확인
        Map<String, Object> explainRow = explainResult.get(0);
        String key = (String) explainRow.get("key");
        String type = (String) explainRow.get("type");

        System.out.println("=== 전체 조회 + 좋아요 순 정렬 EXPLAIN 결과 ===");
        explainResult.forEach(row -> {
            System.out.println("key: " + row.get("key"));
            System.out.println("type: " + row.get("type"));
            System.out.println("rows: " + row.get("rows"));
            System.out.println("Extra: " + row.get("Extra"));
            System.out.println("---");
        });

        // 인덱스 사용 확인 (idx_product_likes 사용)
        assertThat(key).isNotNull();
        assertThat(key).contains("idx_product_likes");
        assertThat(type).isEqualTo("index"); // 인덱스 스캔

        // 성능 측정
        long startTime = System.currentTimeMillis();
        ProductInfoList result = catalogProductFacade.getProducts(null, sort, page, size);
        long endTime = System.currentTimeMillis();
        long executionTime = endTime - startTime;

        System.out.println("실행 시간: " + executionTime + "ms");
        System.out.println("조회된 상품 수: " + result.products().size());

        assertThat(result.products()).hasSize(size);
        assertThat(executionTime).isLessThan(100); // 100ms 이하로 실행되어야 함
    }

    @Test
    @DisplayName("브랜드 필터 + 최신순 정렬: EXPLAIN 분석")
    void testBrandFilterWithLatestSort_explain() {
        // Given
        int page = 0;
        int size = 20;

        // When: EXPLAIN 분석
        String explainQuery = """
            EXPLAIN SELECT p.* FROM product p 
            WHERE p.ref_brand_id = :brandId 
            ORDER BY p.created_at DESC 
            LIMIT :size OFFSET :offset
            """;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> explainResult = entityManager.createNativeQuery(explainQuery)
            .setParameter("brandId", brandId1)
            .setParameter("size", size)
            .setParameter("offset", page * size)
            .getResultList();

        // Then: 인덱스 사용 확인
        Map<String, Object> explainRow = explainResult.get(0);
        String key = (String) explainRow.get("key");

        System.out.println("=== 브랜드 필터 + 최신순 정렬 EXPLAIN 결과 ===");
        explainResult.forEach(row -> {
            System.out.println("key: " + row.get("key"));
            System.out.println("type: " + row.get("type"));
            System.out.println("rows: " + row.get("rows"));
            System.out.println("Extra: " + row.get("Extra"));
            System.out.println("---");
        });

        // 인덱스 사용 확인
        assertThat(key).isNotNull();
        assertThat(key).contains("idx_product_brand_created");
    }

    @Test
    @DisplayName("브랜드 필터 + 가격순 정렬: EXPLAIN 분석")
    void testBrandFilterWithPriceSort_explain() {
        // Given
        int page = 0;
        int size = 20;

        // When: EXPLAIN 분석
        String explainQuery = """
            EXPLAIN SELECT p.* FROM product p 
            WHERE p.ref_brand_id = :brandId 
            ORDER BY p.price ASC 
            LIMIT :size OFFSET :offset
            """;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> explainResult = entityManager.createNativeQuery(explainQuery)
            .setParameter("brandId", brandId1)
            .setParameter("size", size)
            .setParameter("offset", page * size)
            .getResultList();

        // Then: 인덱스 사용 확인
        Map<String, Object> explainRow = explainResult.get(0);
        String key = (String) explainRow.get("key");

        System.out.println("=== 브랜드 필터 + 가격순 정렬 EXPLAIN 결과 ===");
        explainResult.forEach(row -> {
            System.out.println("key: " + row.get("key"));
            System.out.println("type: " + row.get("type"));
            System.out.println("rows: " + row.get("rows"));
            System.out.println("Extra: " + row.get("Extra"));
            System.out.println("---");
        });

        // 인덱스 사용 확인
        assertThat(key).isNotNull();
        assertThat(key).contains("idx_product_brand_price");
    }
}

