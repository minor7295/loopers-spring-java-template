package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.RankingService;
import com.loopers.domain.product.ProductDetail;

import java.util.List;

/**
 * 랭킹 조회 API v1의 데이터 전송 객체(DTO) 컨테이너.
 *
 * @author Loopers
 * @version 1.0
 */
public class RankingV1Dto {
    /**
     * 랭킹 항목 응답 데이터.
     *
     * @param rank 순위 (1부터 시작)
     * @param score 점수
     * @param productId 상품 ID
     * @param name 상품 이름
     * @param price 상품 가격
     * @param stock 상품 재고
     * @param brandId 브랜드 ID
     * @param brandName 브랜드 이름
     * @param likesCount 좋아요 수
     */
    public record RankingItemResponse(
        Long rank,
        Double score,
        Long productId,
        String name,
        Integer price,
        Integer stock,
        Long brandId,
        String brandName,
        Long likesCount
    ) {
        /**
         * RankingService.RankingItem으로부터 RankingItemResponse를 생성합니다.
         *
         * @param item 랭킹 항목
         * @return 생성된 응답 객체
         */
        public static RankingItemResponse from(RankingService.RankingItem item) {
            ProductDetail detail = item.productDetail();
            return new RankingItemResponse(
                item.rank(),
                item.score(),
                detail.getId(),
                detail.getName(),
                detail.getPrice(),
                detail.getStock(),
                detail.getBrandId(),
                detail.getBrandName(),
                detail.getLikesCount()
            );
        }
    }

    /**
     * 랭킹 목록 응답 데이터.
     *
     * @param items 랭킹 항목 목록
     * @param page 현재 페이지 번호
     * @param size 페이지당 항목 수
     * @param hasNext 다음 페이지 존재 여부
     */
    public record RankingsResponse(
        List<RankingItemResponse> items,
        int page,
        int size,
        boolean hasNext
    ) {
        /**
         * RankingService.RankingsResponse로부터 RankingsResponse를 생성합니다.
         *
         * @param response 랭킹 조회 결과
         * @return 생성된 응답 객체
         */
        public static RankingsResponse from(RankingService.RankingsResponse response) {
            List<RankingItemResponse> items = response.items().stream()
                .map(RankingItemResponse::from)
                .toList();

            return new RankingsResponse(
                items,
                response.page(),
                response.size(),
                response.hasNext()
            );
        }
    }
}
