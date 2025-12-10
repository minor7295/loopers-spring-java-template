package com.loopers.domain.coupon;

import java.time.LocalDateTime;

/**
 * 쿠폰 도메인 이벤트.
 * <p>
 * 쿠폰 도메인의 중요한 상태 변화를 나타내는 이벤트들입니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public class CouponEvent {

    /**
     * 쿠폰 적용 이벤트.
     * <p>
     * 쿠폰이 주문에 적용되었을 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID (Long - User.id)
     * @param couponCode 쿠폰 코드
     * @param discountAmount 할인 금액
     * @param appliedAt 쿠폰 적용 시각
     */
    public record CouponApplied(
            Long orderId,
            Long userId,
            String couponCode,
            Integer discountAmount,
            LocalDateTime appliedAt
    ) {
        public CouponApplied {
            if (orderId == null) {
                throw new IllegalArgumentException("orderId는 필수입니다.");
            }
            if (userId == null) {
                throw new IllegalArgumentException("userId는 필수입니다.");
            }
            if (couponCode == null || couponCode.isBlank()) {
                throw new IllegalArgumentException("couponCode는 필수입니다.");
            }
            if (discountAmount == null || discountAmount < 0) {
                throw new IllegalArgumentException("discountAmount는 0 이상이어야 합니다.");
            }
        }

        /**
         * 쿠폰 적용 정보로부터 CouponApplied 이벤트를 생성합니다.
         *
         * @param orderId 주문 ID
         * @param userId 사용자 ID
         * @param couponCode 쿠폰 코드
         * @param discountAmount 할인 금액
         * @return CouponApplied 이벤트
         */
        public static CouponApplied of(Long orderId, Long userId, String couponCode, Integer discountAmount) {
            return new CouponApplied(
                    orderId,
                    userId,
                    couponCode,
                    discountAmount,
                    LocalDateTime.now()
            );
        }
    }
}

