package com.loopers.interfaces.event.user;

import com.loopers.application.user.PointEventHandler;
import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.user.PointEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 포인트 이벤트 리스너.
 * <p>
 * 포인트 사용 이벤트와 주문 취소 이벤트를 받아서 포인트 사용/환불 처리를 수행하는 인터페이스 레이어의 어댑터입니다.
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
public class PointEventListener {

    private final PointEventHandler pointEventHandler;

    /**
     * 포인트 사용 이벤트를 처리합니다.
     * <p>
     * 트랜잭션 커밋 후 비동기로 실행되어 포인트 사용 처리를 수행합니다.
     * </p>
     *
     * @param event 포인트 사용 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePointUsed(PointEvent.PointUsed event) {
        try {
            pointEventHandler.handlePointUsed(event);
        } catch (Exception e) {
            log.error("포인트 사용 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }

    /**
     * 주문 취소 이벤트를 처리합니다.
     * <p>
     * 트랜잭션 커밋 후 비동기로 실행되어 포인트 환불 처리를 수행합니다.
     * </p>
     *
     * @param event 주문 취소 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCanceled(OrderEvent.OrderCanceled event) {
        try {
            pointEventHandler.handleOrderCanceled(event);
        } catch (Exception e) {
            log.error("주문 취소 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }
}

