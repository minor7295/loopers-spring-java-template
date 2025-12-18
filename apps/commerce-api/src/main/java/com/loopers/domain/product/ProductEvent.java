package com.loopers.domain.product;

import java.time.LocalDateTime;

/**
 * 상품 도메인 이벤트.
 * <p>
 * 상품 도메인의 중요한 상태 변화를 나타내는 이벤트들입니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public class ProductEvent {

    /**
     * 상품 상세 페이지 조회 이벤트.
     * <p>
     * 상품 상세 페이지가 조회되었을 때 발행되는 이벤트입니다.
     * 메트릭 집계를 위해 사용됩니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param userId 사용자 ID (null 가능 - 비로그인 사용자)
     * @param occurredAt 이벤트 발생 시각
     */
    public record ProductViewed(
            Long productId,
            Long userId,
            LocalDateTime occurredAt
    ) {
        public ProductViewed {
            if (productId == null) {
                throw new IllegalArgumentException("productId는 필수입니다.");
            }
        }

        /**
         * 상품 ID로부터 ProductViewed 이벤트를 생성합니다.
         *
         * @param productId 상품 ID
         * @return ProductViewed 이벤트
         */
        public static ProductViewed from(Long productId) {
            return new ProductViewed(productId, null, LocalDateTime.now());
        }

        /**
         * 상품 ID와 사용자 ID로부터 ProductViewed 이벤트를 생성합니다.
         *
         * @param productId 상품 ID
         * @param userId 사용자 ID
         * @return ProductViewed 이벤트
         */
        public static ProductViewed from(Long productId, Long userId) {
            return new ProductViewed(productId, userId, LocalDateTime.now());
        }
    }
}
