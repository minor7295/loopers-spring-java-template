package com.loopers.application.integration;

import com.loopers.domain.order.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 데이터 플랫폼 전송 이벤트 리스너.
 * <p>
 * 주문 완료/취소 이벤트를 받아 데이터 플랫폼에 전송합니다.
 * </p>
 * <p>
 * <b>트랜잭션 전략:</b>
 * <ul>
 *   <li><b>AFTER_COMMIT:</b> 주문 트랜잭션이 커밋된 후에 실행되어 데이터 일관성 보장</li>
 *   <li><b>@Async:</b> 비동기로 실행하여 주문 처리 성능에 영향을 주지 않음</li>
 * </ul>
 * </p>
 * <p>
 * <b>주의사항:</b>
 * <ul>
 *   <li>데이터 플랫폼 전송 실패는 로그만 기록 (주문 처리에는 영향 없음)</li>
 *   <li>재시도는 외부 시스템(메시지 큐 등)에서 처리하거나 별도 스케줄러로 처리</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataPlatformEventListener {

    // TODO: 데이터 플랫폼 전송 클라이언트 주입
    // private final DataPlatformClient dataPlatformClient;

    /**
     * 주문 완료 이벤트를 처리하여 데이터 플랫폼에 전송합니다.
     *
     * @param event 주문 완료 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCompleted(OrderEvent.OrderCompleted event) {
        try {
            // TODO: 데이터 플랫폼에 주문 완료 데이터 전송
            // dataPlatformClient.sendOrderCompleted(event);
            
            log.info("주문 완료 데이터 플랫폼 전송 완료. (orderId: {}, userId: {}, totalAmount: {})",
                event.orderId(), event.userId(), event.totalAmount());
        } catch (Exception e) {
            // 데이터 플랫폼 전송 실패는 로그만 기록
            log.error("주문 완료 데이터 플랫폼 전송 중 오류 발생. (orderId: {})", event.orderId(), e);
        }
    }

    /**
     * 주문 취소 이벤트를 처리하여 데이터 플랫폼에 전송합니다.
     *
     * @param event 주문 취소 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCanceled(OrderEvent.OrderCanceled event) {
        try {
            // TODO: 데이터 플랫폼에 주문 취소 데이터 전송
            // dataPlatformClient.sendOrderCanceled(event);
            
            log.info("주문 취소 데이터 플랫폼 전송 완료. (orderId: {}, userId: {}, reason: {})",
                event.orderId(), event.userId(), event.reason());
        } catch (Exception e) {
            // 데이터 플랫폼 전송 실패는 로그만 기록
            log.error("주문 취소 데이터 플랫폼 전송 중 오류 발생. (orderId: {})", event.orderId(), e);
        }
    }
}
