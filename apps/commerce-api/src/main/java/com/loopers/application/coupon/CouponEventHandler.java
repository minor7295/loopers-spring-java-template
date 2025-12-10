package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponEvent;
import com.loopers.domain.coupon.CouponEventPublisher;
import com.loopers.domain.order.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 이벤트 핸들러.
 * <p>
 * 주문 생성 이벤트를 받아 쿠폰 사용 처리를 수행하는 애플리케이션 로직을 처리합니다.
 * </p>
 * <p>
 * <b>DDD/EDA 관점:</b>
 * <ul>
 *   <li><b>책임 분리:</b> CouponService는 쿠폰 도메인 비즈니스 로직, CouponEventHandler는 이벤트 처리 로직</li>
 *   <li><b>이벤트 핸들러:</b> 이벤트를 받아서 처리하는 역할을 명확히 나타냄</li>
 *   <li><b>도메인 경계 준수:</b> 쿠폰 도메인은 쿠폰 적용 이벤트만 발행하고, 주문 도메인은 자신의 상태를 관리</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponEventHandler {

    private final CouponService couponService;
    private final CouponEventPublisher couponEventPublisher;

    /**
     * 주문 생성 이벤트를 처리하여 쿠폰을 사용하고 쿠폰 적용 이벤트를 발행합니다.
     * <p>
     * 쿠폰 코드가 있는 경우에만 쿠폰 사용 처리를 수행합니다.
     * 쿠폰 적용 후 CouponApplied 이벤트를 발행하여 주문 도메인이 자신의 상태를 업데이트하도록 합니다.
     * </p>
     *
     * @param event 주문 생성 이벤트
     */
    @Transactional
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        // 쿠폰 코드가 없는 경우 처리하지 않음
        if (event.couponCode() == null || event.couponCode().isBlank()) {
            log.debug("쿠폰 코드가 없어 쿠폰 사용 처리를 건너뜁니다. (orderId: {})", event.orderId());
            return;
        }

        try {
            // 쿠폰 사용 처리 (쿠폰 사용 마킹 및 할인 금액 계산)
            Integer discountAmount = couponService.applyCoupon(
                    event.userId(),
                    event.couponCode(),
                    event.subtotal()
            );

            // ✅ 도메인 이벤트 발행: 쿠폰이 적용되었음 (과거 사실)
            // 주문 도메인이 이 이벤트를 구독하여 자신의 상태를 업데이트함
            couponEventPublisher.publish(CouponEvent.CouponApplied.of(
                    event.orderId(),
                    event.userId(),
                    event.couponCode(),
                    discountAmount
            ));

            log.info("쿠폰 사용 처리 완료. (orderId: {}, couponCode: {}, discountAmount: {})",
                    event.orderId(), event.couponCode(), discountAmount);
        } catch (Exception e) {
            // 쿠폰 사용 처리 실패는 로그만 기록 (주문은 이미 생성되었으므로)
            log.error("쿠폰 사용 처리 중 오류 발생. (orderId: {}, couponCode: {})",
                    event.orderId(), event.couponCode(), e);
        }
    }
}

