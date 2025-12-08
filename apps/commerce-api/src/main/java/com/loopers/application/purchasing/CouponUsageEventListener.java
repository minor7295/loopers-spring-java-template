package com.loopers.application.purchasing;

import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.order.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 쿠폰 사용 이벤트 리스너.
 * <p>
 * 주문 생성 이벤트를 받아 쿠폰 사용 처리를 수행합니다.
 * </p>
 * <p>
 * <b>비동기 처리 이유:</b>
 * 쿠폰 사용 처리는 주문 생성의 부가 로직입니다. 주문 생성 시 할인 금액 계산은 동기로 처리하고,
 * 실제 쿠폰 사용 상태 업데이트는 비동기로 분리하여 주문 처리 성능에 영향을 주지 않습니다.
 * </p>
 * <p>
 * <b>트랜잭션 전략:</b>
 * <ul>
 *   <li><b>AFTER_COMMIT:</b> 주문 트랜잭션이 커밋된 후에 실행되어 주문과 쿠폰 사용 처리를 분리</li>
 *   <li><b>REQUIRES_NEW:</b> 별도 트랜잭션으로 실행하여 쿠폰 사용 처리 실패가 주문에 영향을 주지 않도록 함</li>
 *   <li><b>@Async:</b> 비동기로 실행하여 주문 처리 성능에 영향을 주지 않음</li>
 * </ul>
 * </p>
 * <p>
 * <b>주의사항:</b>
 * 주문 생성 실패 시 쿠폰 사용이 롤백되지 않을 수 있으나, 주문 생성이 실패하면 쿠폰 사용도 의미가 없으므로
 * 별도 보상 트랜잭션으로 처리하거나, 주문 생성 전에 쿠폰 사용 처리를 하는 것을 고려할 수 있습니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponUsageEventListener {

    private final CouponService couponService;

    /**
     * 주문 생성 이벤트를 처리하여 쿠폰을 사용합니다.
     * <p>
     * 쿠폰 코드가 있는 경우에만 쿠폰 사용 처리를 수행합니다.
     * </p>
     *
     * @param event 주문 생성 이벤트
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        // 쿠폰 코드가 없는 경우 처리하지 않음
        if (event.couponCode() == null || event.couponCode().isBlank()) {
            log.debug("쿠폰 코드가 없어 쿠폰 사용 처리를 건너뜁니다. (orderId: {})", event.orderId());
            return;
        }

        try {
            // 쿠폰 사용 처리
            // 주문 생성 시 이미 할인 금액이 계산되어 있으므로, 여기서는 쿠폰 사용 상태만 업데이트
            // 실제로는 쿠폰 사용 처리가 주문 생성 전에 이루어져야 하지만,
            // 이벤트 기반 분리를 위해 주문 생성 후에 처리하도록 변경
            Integer discountAmount = couponService.applyCoupon(
                event.userId(),
                event.couponCode(),
                event.subtotal()
            );

            log.info("쿠폰 사용 처리 완료. (orderId: {}, couponCode: {}, discountAmount: {})",
                event.orderId(), event.couponCode(), discountAmount);
        } catch (Exception e) {
            // 쿠폰 사용 처리 실패는 로그만 기록 (주문은 이미 생성되었으므로)
            log.error("쿠폰 사용 처리 중 오류 발생. (orderId: {}, couponCode: {})",
                event.orderId(), event.couponCode(), e);
        }
    }
}
