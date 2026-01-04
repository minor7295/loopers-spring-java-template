package com.loopers.interfaces.event.order;

import com.loopers.application.order.OrderEventHandler;
import com.loopers.domain.coupon.CouponEvent;
import com.loopers.domain.payment.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 이벤트 리스너.
 * <p>
 * 결제 완료/실패 이벤트와 쿠폰 적용 이벤트를 받아서 주문 상태를 업데이트하는 인터페이스 레이어의 어댑터입니다.
 * </p>
 * <p>
 * <b>레이어 역할:</b>
 * <ul>
 *   <li><b>인터페이스 레이어:</b> 외부 이벤트(도메인 이벤트)를 받아서 애플리케이션 핸들러를 호출하는 어댑터</li>
 *   <li><b>비즈니스 로직 없음:</b> 단순히 이벤트를 받아서 애플리케이션 핸들러를 호출하는 역할만 수행</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderEventHandler orderEventHandler;

    /**
     * 결제 완료 이벤트를 처리합니다.
     * <p>
     * 트랜잭션 커밋 후 실행되어 주문 상태를 COMPLETED로 업데이트합니다.
     * </p>
     *
     * @param event 결제 완료 이벤트
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentCompleted(PaymentEvent.PaymentCompleted event) {
        try {
            orderEventHandler.handlePaymentCompleted(event);
        } catch (Exception e) {
            log.error("결제 완료 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }

    /**
     * 쿠폰 적용 이벤트를 처리합니다.
     * <p>
     * 트랜잭션 커밋 후 비동기로 실행되어 주문에 할인 금액을 적용합니다.
     * </p>
     *
     * @param event 쿠폰 적용 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCouponApplied(CouponEvent.CouponApplied event) {
        try {
            orderEventHandler.handleCouponApplied(event);
        } catch (Exception e) {
            log.error("쿠폰 적용 이벤트 처리 중 오류 발생. (orderId: {}, couponCode: {})",
                    event.orderId(), event.couponCode(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }

    /**
     * 결제 실패 이벤트를 처리합니다.
     * <p>
     * 트랜잭션 커밋 후 비동기로 실행되어 주문 취소 처리를 수행합니다.
     * </p>
     *
     * @param event 결제 실패 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentFailed(PaymentEvent.PaymentFailed event) {
        try {
            orderEventHandler.handlePaymentFailed(event);
        } catch (Exception e) {
            log.error("결제 실패 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }
}
