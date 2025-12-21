package com.loopers.application.ranking;

import com.loopers.application.brand.BrandService;
import com.loopers.application.product.ProductService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductDetail;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.zset.ZSetEntry;
import com.loopers.zset.RedisZSetTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 랭킹 조회 서비스.
 * <p>
 * Redis ZSET에서 랭킹을 조회하고 상품 정보를 Aggregation하여 제공합니다.
 * </p>
 * <p>
 * <b>설계 원칙:</b>
 * <ul>
 *   <li>Application 유즈케이스: Ranking은 도메인이 아닌 파생 View로 취급</li>
 *   <li>상품 정보 Aggregation: 상품 ID만이 아닌 상품 정보 포함</li>
 *   <li>배치 조회: N+1 쿼리 문제 방지</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {
    private final RedisZSetTemplate zSetTemplate;
    private final RankingKeyGenerator keyGenerator;
    private final ProductService productService;
    private final BrandService brandService;

    /**
     * 랭킹을 조회합니다 (페이징).
     * <p>
     * ZSET에서 상위 N개를 조회하고, 상품 정보를 Aggregation하여 반환합니다.
     * </p>
     *
     * @param date 날짜 (yyyyMMdd 형식의 문자열 또는 LocalDate)
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지당 항목 수
     * @return 랭킹 조회 결과
     */
    @Transactional(readOnly = true)
    public RankingsResponse getRankings(LocalDate date, int page, int size) {
        String key = keyGenerator.generateDailyKey(date);
        long start = (long) page * size;
        long end = start + size - 1;

        // ZSET에서 Top N 조회
        List<ZSetEntry> entries = zSetTemplate.getTopRankings(key, start, end);

        if (entries.isEmpty()) {
            return RankingsResponse.empty(page, size);
        }

        // 상품 ID 추출
        List<Long> productIds = entries.stream()
            .map(entry -> Long.parseLong(entry.member()))
            .toList();

        // 상품 정보 배치 조회
        List<Product> products = productService.getProducts(productIds);

        // 상품 ID → Product Map 생성
        Map<Long, Product> productMap = products.stream()
            .collect(Collectors.toMap(Product::getId, product -> product));

        // 브랜드 ID 수집
        List<Long> brandIds = products.stream()
            .map(Product::getBrandId)
            .distinct()
            .toList();

        // 브랜드 배치 조회
        Map<Long, Brand> brandMap = brandService.getBrands(brandIds).stream()
            .collect(Collectors.toMap(Brand::getId, brand -> brand));

        // 랭킹 항목 생성 (순위, 점수, 상품 정보 포함)
        List<RankingItem> rankingItems = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ZSetEntry entry = entries.get(i);
            Long productId = Long.parseLong(entry.member());
            Long rank = start + i + 1; // 1-based 순위

            Product product = productMap.get(productId);
            if (product == null) {
                log.warn("랭킹에 포함된 상품을 찾을 수 없습니다: productId={}", productId);
                continue;
            }

            Brand brand = brandMap.get(product.getBrandId());
            if (brand == null) {
                log.warn("상품의 브랜드를 찾을 수 없습니다: productId={}, brandId={}", 
                    productId, product.getBrandId());
                continue;
            }

            ProductDetail productDetail = ProductDetail.from(
                product, 
                brand.getName(), 
                product.getLikeCount()
            );

            rankingItems.add(new RankingItem(
                rank,
                entry.score(),
                productDetail
            ));
        }

        // 전체 랭킹 개수 조회 (ZSET 크기)
        Long totalSize = zSetTemplate.getSize(key);
        boolean hasNext = (start + size) < totalSize;

        return new RankingsResponse(rankingItems, page, size, hasNext);
    }

    /**
     * 특정 상품의 순위를 조회합니다.
     * <p>
     * 상품이 랭킹에 없으면 null을 반환합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param date 날짜
     * @return 순위 (1부터 시작, 없으면 null)
     */
    @Transactional(readOnly = true)
    public Long getProductRank(Long productId, LocalDate date) {
        String key = keyGenerator.generateDailyKey(date);
        Long rank = zSetTemplate.getRank(key, String.valueOf(productId));

        if (rank == null) {
            return null;
        }

        // 0-based → 1-based 변환
        return rank + 1;
    }

    /**
     * 랭킹 조회 결과.
     *
     * @param items 랭킹 항목 목록
     * @param page 현재 페이지 번호
     * @param size 페이지당 항목 수
     * @param hasNext 다음 페이지 존재 여부
     */
    public record RankingsResponse(
        List<RankingItem> items,
        int page,
        int size,
        boolean hasNext
    ) {
        /**
         * 빈 랭킹 조회 결과를 생성합니다.
         */
        public static RankingsResponse empty(int page, int size) {
            return new RankingsResponse(List.of(), page, size, false);
        }
    }

    /**
     * 랭킹 항목 (순위, 점수, 상품 정보).
     *
     * @param rank 순위 (1부터 시작)
     * @param score 점수
     * @param productDetail 상품 상세 정보
     */
    public record RankingItem(
        Long rank,
        Double score,
        ProductDetail productDetail
    ) {
    }
}
