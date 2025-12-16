package com.loopers.interfaces.event.kafka;

import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.order.OrderEvent;
import com.loopers.infrastructure.kafka.KafkaEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Kafka Bridge 이벤트 리스너.
 * <p>
 * ApplicationEvent를 구독하여 외부 시스템에 필요한 이벤트만 Kafka로 전송합니다.
 * 같은 애플리케이션 내부 통신은 ApplicationEvent로 처리하고,
 * 외부 시스템(데이터 플랫폼, 분석 시스템 등) 연동을 위해 Kafka로 전송합니다.
 * </p>
 * <p>
 * <b>하이브리드 접근법:</b>
 * <ul>
 *   <li><b>ApplicationEvent:</b> 같은 애플리케이션 내부 통신 (낮은 지연시간, 트랜잭션 제어)</li>
 *   <li><b>Kafka:</b> 외부 시스템 연동 (이벤트 지속성, 재처리, 다른 서비스와 공유)</li>
 * </ul>
 * </p>
 * <p>
 * <b>트랜잭션 전략:</b>
 * <ul>
 *   <li><b>AFTER_COMMIT:</b> 트랜잭션 커밋 후 Kafka로 전송하여 데이터 일관성 보장</li>
 *   <li><b>@Async:</b> 비동기로 실행하여 내부 처리 성능에 영향을 주지 않음</li>
 * </ul>
 * </p>
 * <p>
 * <b>에러 처리:</b>
 * <ul>
 *   <li>Kafka 전송 실패는 로그만 기록 (내부 처리에는 영향 없음)</li>
 *   <li>외부 시스템 전송 실패는 내부 비즈니스 로직에 영향을 주지 않음</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaBridgeEventListener {

    private final KafkaEventPublisher kafkaEventPublisher;

    // ========== Order Events ==========

    /**
     * 주문 생성 이벤트를 Kafka로 전송 (외부 시스템용).
     * <p>
     * 외부 시스템(데이터 플랫폼, 분석 시스템 등)에 필요한 이벤트만 Kafka로 전송합니다.
     * 파티션 키: orderId (주문별 이벤트 순서 보장)
     * </p>
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        try {
            kafkaEventPublisher.publish(event);
        } catch (Exception e) {
            log.error("주문 생성 이벤트 Kafka 전송 실패: orderId={}", event.orderId(), e);
            // 외부 시스템 전송 실패는 내부 처리에 영향 없음
        }
    }

    // ========== Like Events ==========

    /**
     * 좋아요 추가 이벤트를 Kafka로 전송 (외부 시스템용).
     * <p>
     * 상품 메트릭 집계를 위해 Kafka로 전송합니다.
     * 파티션 키: productId (상품별 좋아요 수 집계)
     * </p>
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLikeAdded(LikeEvent.LikeAdded event) {
        try {
            kafkaEventPublisher.publish(event);
        } catch (Exception e) {
            log.error("좋아요 추가 이벤트 Kafka 전송 실패: productId={}, userId={}", 
                event.productId(), event.userId(), e);
        }
    }

    /**
     * 좋아요 취소 이벤트를 Kafka로 전송 (외부 시스템용).
     * <p>
     * 상품 메트릭 집계를 위해 Kafka로 전송합니다.
     * 파티션 키: productId (상품별 좋아요 수 집계)
     * </p>
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLikeRemoved(LikeEvent.LikeRemoved event) {
        try {
            kafkaEventPublisher.publish(event);
        } catch (Exception e) {
            log.error("좋아요 취소 이벤트 Kafka 전송 실패: productId={}, userId={}", 
                event.productId(), event.userId(), e);
        }
    }
}
