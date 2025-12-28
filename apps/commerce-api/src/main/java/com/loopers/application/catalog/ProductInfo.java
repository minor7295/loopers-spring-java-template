package com.loopers.application.catalog;

import com.loopers.domain.product.ProductDetail;

/**
 * 상품 상세 정보를 담는 레코드.
 *
 * @param productDetail 상품 상세 정보 (Product + Brand + 좋아요 수)
 * @param rank 랭킹 순위 (1부터 시작, 랭킹에 없으면 null)
 */
public record ProductInfo(ProductDetail productDetail, Long rank) {
    /**
     * 랭킹 정보 없이 ProductInfo를 생성합니다.
     *
     * @param productDetail 상품 상세 정보
     * @return ProductInfo (rank는 null)
     */
    public static ProductInfo withoutRank(ProductDetail productDetail) {
        return new ProductInfo(productDetail, null);
    }

    /**
     * 랭킹 정보와 함께 ProductInfo를 생성합니다.
     *
     * @param productDetail 상품 상세 정보
     * @param rank 랭킹 순위 (1부터 시작, 랭킹에 없으면 null)
     * @return ProductInfo
     */
    public static ProductInfo withRank(ProductDetail productDetail, Long rank) {
        return new ProductInfo(productDetail, rank);
    }
}

