package com.loopers.application.order;

import com.loopers.domain.coupon.CouponEvent;
import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 이벤트 핸들러.
 * <p>
 * 결제 완료/실패 이벤트와 쿠폰 적용 이벤트를 받아 주문 상태를 업데이트하는 애플리케이션 로직을 처리합니다.
 * </p>
 * <p>
 * <b>DDD/EDA 관점:</b>
 * <ul>
 *   <li><b>책임 분리:</b> OrderService는 주문 도메인 비즈니스 로직, OrderEventHandler는 이벤트 처리 로직</li>
 *   <li><b>이벤트 핸들러:</b> 이벤트를 받아서 처리하는 역할을 명확히 나타냄</li>
 *   <li><b>도메인 경계 준수:</b> 주문 도메인은 자신의 상태만 관리하며, 다른 도메인의 이벤트를 구독하여 반응</li>
 *   <li><b>느슨한 결합:</b> UserService나 PurchasingFacade를 직접 참조하지 않고, 이벤트만 발행</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventHandler {

    private final OrderService orderService;

    /**
     * 결제 완료 이벤트를 처리하여 주문 상태를 COMPLETED로 업데이트합니다.
     * <p>
     * <b>트랜잭션 전략:</b>
     * <ul>
     *   <li><b>AFTER_COMMIT:</b> 원래 트랜잭션이 이미 커밋되었으므로 자동으로 새 트랜잭션이 생성됨</li>
     * </ul>
     * </p>
     *
     * @param event 결제 완료 이벤트
     */
    @Transactional
    public void handlePaymentCompleted(PaymentEvent.PaymentCompleted event) {
        Order order = orderService.getOrder(event.orderId()).orElse(null);
        if (order == null) {
            log.warn("결제 완료 이벤트 처리 시 주문을 찾을 수 없습니다. (orderId: {})", event.orderId());
            return;
        }

        // 이미 완료된 주문인 경우 처리하지 않음
        if (order.isCompleted()) {
            log.debug("이미 완료된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", event.orderId());
            return;
        }

        // 이미 취소된 주문인 경우 처리하지 않음 (race condition 방지)
        // 예: 결제 타임아웃으로 인해 주문이 취소되었지만, 이후 PG 상태 확인에서 SUCCESS가 반환된 경우
        if (order.isCanceled()) {
            log.warn("이미 취소된 주문입니다. 결제 완료 처리를 건너뜁니다. (orderId: {}, transactionKey: {})", 
                    event.orderId(), event.transactionKey());
            return;
        }

        // 주문 완료 처리
        orderService.completeOrder(event.orderId());
        log.info("결제 완료로 인한 주문 상태 업데이트 완료. (orderId: {}, transactionKey: {})",
                event.orderId(), event.transactionKey());
    }

    /**
     * 결제 실패 이벤트를 처리하여 주문을 취소합니다.
     * <p>
     * 주문 상태만 CANCELED로 변경하고 OrderCanceled 이벤트를 발행합니다.
     * 리소스 원복(재고, 포인트)은 OrderCanceled 이벤트를 구독하는 별도 핸들러에서 처리합니다.
     * </p>
     * <p>
     * <b>트랜잭션 전략:</b>
     * <ul>
     *   <li><b>AFTER_COMMIT:</b> 원래 트랜잭션이 이미 커밋되었으므로 자동으로 새 트랜잭션이 생성됨</li>
     * </ul>
     * </p>
     * <p>
     * <b>DDD/EDA 관점:</b>
     * <ul>
     *   <li><b>도메인 경계 준수:</b> 주문 도메인이 자신의 상태를 관리하며, 결제 실패 이벤트를 구독하여 반응</li>
     *   <li><b>느슨한 결합:</b> 리소스 원복은 별도 이벤트 핸들러에서 처리하여 도메인 간 결합 제거</li>
     * </ul>
     * </p>
     *
     * @param event 결제 실패 이벤트
     */
    @Transactional
    public void handlePaymentFailed(PaymentEvent.PaymentFailed event) {
        Order order = orderService.getOrder(event.orderId()).orElse(null);
        if (order == null) {
            log.warn("결제 실패 이벤트 처리 시 주문을 찾을 수 없습니다. (orderId: {})", event.orderId());
            return;
        }

        // 이미 취소된 주문인 경우 처리하지 않음
        if (order.isCanceled()) {
            log.debug("이미 취소된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", event.orderId());
            return;
        }

        // 주문 취소 (OrderCanceled 이벤트 발행 포함)
        // PaymentFailed 이벤트에 포함된 refundPointAmount 사용
        orderService.cancelOrder(event.orderId(), event.reason(), event.refundPointAmount());
        log.info("결제 실패로 인한 주문 취소 완료. (orderId: {}, reason: {}, refundPointAmount: {})",
                event.orderId(), event.reason(), event.refundPointAmount());
    }


    /**
     * 쿠폰 적용 이벤트를 처리하여 주문에 할인 금액을 적용합니다.
     * <p>
     * 쿠폰 도메인에서 쿠폰이 적용되었다는 이벤트를 받아 주문 도메인이 자신의 상태를 업데이트합니다.
     * </p>
     *
     * @param event 쿠폰 적용 이벤트
     */
    @Transactional
    public void handleCouponApplied(CouponEvent.CouponApplied event) {
        try {
            // 주문에 할인 금액 적용 (totalAmount 업데이트)
            orderService.applyCouponDiscount(event.orderId(), event.discountAmount());
            
            log.info("쿠폰 할인 금액이 주문에 적용되었습니다. (orderId: {}, couponCode: {}, discountAmount: {})",
                    event.orderId(), event.couponCode(), event.discountAmount());
        } catch (Exception e) {
            // 주문 업데이트 실패는 로그만 기록 (쿠폰은 이미 적용되었으므로)
            log.error("쿠폰 적용 이벤트 처리 중 오류 발생. (orderId: {}, couponCode: {})",
                    event.orderId(), event.couponCode(), e);
        }
    }
}

