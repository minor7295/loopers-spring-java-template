package com.loopers.interfaces.event.coupon;

import com.loopers.application.coupon.CouponEventHandler;
import com.loopers.domain.coupon.CouponEvent;
import com.loopers.domain.coupon.CouponEventPublisher;
import com.loopers.domain.order.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 쿠폰 이벤트 리스너.
 * <p>
 * 주문 생성 이벤트를 받아서 쿠폰 사용 처리를 수행하는 인터페이스 레이어의 어댑터입니다.
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
public class CouponEventListener {

    private final CouponEventHandler couponEventHandler;
    private final CouponEventPublisher couponEventPublisher;

    /**
     * 주문 생성 이벤트를 처리합니다.
     * <p>
     * 트랜잭션 커밋 후 비동기로 실행되어 쿠폰 사용 처리를 수행합니다.
     * </p>
     *
     * @param event 주문 생성 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        try {
            couponEventHandler.handleOrderCreated(event);
        } catch (Exception e) {
            // ✅ 도메인 이벤트 발행: 쿠폰 적용이 실패했음 (과거 사실)
            // 이벤트 핸들러에서 예외가 발생했으므로 실패 이벤트를 발행
            
            // Optimistic Locking 실패는 정상적인 동시성 제어 결과이므로 별도 처리
            String failureReason;
            if (e instanceof ObjectOptimisticLockingFailureException || 
                e instanceof OptimisticLockingFailureException) {
                failureReason = "쿠폰이 이미 사용되었습니다. (동시성 충돌)";
            } else {
                failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            
            couponEventPublisher.publish(CouponEvent.CouponApplicationFailed.of(
                    event.orderId(),
                    event.userId(),
                    event.couponCode(),
                    failureReason
            ));
            
            // Optimistic Locking 실패는 정상적인 동시성 제어 결과이므로 WARN 레벨로 로깅
            if (e instanceof ObjectOptimisticLockingFailureException || 
                e instanceof OptimisticLockingFailureException) {
                log.warn("쿠폰 사용 중 낙관적 락 충돌 발생. (orderId: {}, couponCode: {})", 
                        event.orderId(), event.couponCode());
            } else {
                log.error("주문 생성 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            }
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }
}
