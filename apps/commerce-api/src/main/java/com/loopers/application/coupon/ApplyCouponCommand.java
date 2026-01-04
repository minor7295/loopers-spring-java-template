package com.loopers.application.coupon;

/**
 * 쿠폰 적용 명령.
 * <p>
 * 쿠폰 적용을 위한 명령 객체입니다.
 * </p>
 *
 * @param userId 사용자 ID
 * @param couponCode 쿠폰 코드
 * @param subtotal 주문 소계 금액
 */
public record ApplyCouponCommand(
    Long userId,
    String couponCode,
    Integer subtotal
) {
    public ApplyCouponCommand {
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (couponCode == null || couponCode.isBlank()) {
            throw new IllegalArgumentException("couponCode는 필수입니다.");
        }
        if (subtotal == null || subtotal < 0) {
            throw new IllegalArgumentException("subtotal은 0 이상이어야 합니다.");
        }
    }
}

