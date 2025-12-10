package com.loopers.domain.coupon;

/**
 * 쿠폰 도메인 이벤트 발행 인터페이스.
 * <p>
 * DIP를 준수하여 도메인 레이어에서 이벤트 발행 인터페이스를 정의합니다.
 * 구현은 인프라 레이어에서 제공됩니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface CouponEventPublisher {

    /**
     * 쿠폰 적용 이벤트를 발행합니다.
     *
     * @param event 쿠폰 적용 이벤트
     */
    void publish(CouponEvent.CouponApplied event);

    /**
     * 쿠폰 적용 실패 이벤트를 발행합니다.
     *
     * @param event 쿠폰 적용 실패 이벤트
     */
    void publish(CouponEvent.CouponApplicationFailed event);
}

