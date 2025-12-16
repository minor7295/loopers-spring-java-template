package com.loopers.infrastructure.kafka;

import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.order.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Kafka 이벤트 발행 서비스.
 * <p>
 * 외부 시스템에 필요한 이벤트만 Kafka로 발행하는 인프라 레이어 서비스입니다.
 * 상품 메트릭 집계를 위해 OrderCreated, LikeAdded, LikeRemoved 이벤트를 전송합니다.
 * </p>
 * <p>
 * <b>파티션 키 전략:</b>
 * <ul>
 *   <li><b>order-events:</b> orderId (주문별 이벤트 순서 보장)</li>
 *   <li><b>like-events:</b> productId (상품별 좋아요 수 집계)</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // ========== Order Events ==========

    /**
     * 주문 생성 이벤트를 Kafka로 발행합니다.
     * <p>
     * 외부 시스템(데이터 플랫폼, 분석 시스템 등)에 필요한 이벤트만 Kafka로 발행합니다.
     * </p>
     *
     * @param event 주문 생성 이벤트
     */
    public void publish(OrderEvent.OrderCreated event) {
        try {
            String partitionKey = event.orderId().toString();
            kafkaTemplate.send("order-events", partitionKey, event);
            log.debug("주문 생성 이벤트를 Kafka로 발행: orderId={}", event.orderId());
        } catch (Exception e) {
            log.error("주문 생성 이벤트 Kafka 발행 실패: orderId={}", event.orderId(), e);
            throw new RuntimeException("Kafka 이벤트 발행 실패", e);
        }
    }

    // ========== Like Events ==========

    /**
     * 좋아요 추가 이벤트를 Kafka로 발행합니다.
     *
     * @param event 좋아요 추가 이벤트
     */
    public void publish(LikeEvent.LikeAdded event) {
        try {
            String partitionKey = event.productId().toString();
            kafkaTemplate.send("like-events", partitionKey, event);
            log.debug("좋아요 추가 이벤트를 Kafka로 발행: productId={}, userId={}", 
                event.productId(), event.userId());
        } catch (Exception e) {
            log.error("좋아요 추가 이벤트 Kafka 발행 실패: productId={}, userId={}", 
                event.productId(), event.userId(), e);
            throw new RuntimeException("Kafka 이벤트 발행 실패", e);
        }
    }

    /**
     * 좋아요 취소 이벤트를 Kafka로 발행합니다.
     *
     * @param event 좋아요 취소 이벤트
     */
    public void publish(LikeEvent.LikeRemoved event) {
        try {
            String partitionKey = event.productId().toString();
            kafkaTemplate.send("like-events", partitionKey, event);
            log.debug("좋아요 취소 이벤트를 Kafka로 발행: productId={}, userId={}", 
                event.productId(), event.userId());
        } catch (Exception e) {
            log.error("좋아요 취소 이벤트 Kafka 발행 실패: productId={}, userId={}", 
                event.productId(), event.userId(), e);
            throw new RuntimeException("Kafka 이벤트 발행 실패", e);
        }
    }
}
