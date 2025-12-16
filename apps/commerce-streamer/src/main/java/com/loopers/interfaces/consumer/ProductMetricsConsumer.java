package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.metrics.ProductMetricsService;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.event.LikeEvent;
import com.loopers.domain.event.OrderEvent;
import com.loopers.domain.event.ProductEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 상품 메트릭 집계 Kafka Consumer.
 * <p>
 * Kafka에서 이벤트를 수취하여 상품 메트릭을 집계합니다.
 * 좋아요 수, 판매량, 상세 페이지 조회 수 등을 `product_metrics` 테이블에 upsert합니다.
 * </p>
 * <p>
 * <b>처리 이벤트:</b>
 * <ul>
 *   <li><b>like-events:</b> LikeAdded, LikeRemoved (좋아요 수 집계)</li>
 *   <li><b>order-events:</b> OrderCreated (판매량 집계)</li>
 *   <li><b>product-events:</b> ProductViewed (조회 수 집계)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Manual Ack:</b>
 * <ul>
 *   <li>이벤트 처리 성공 후 수동으로 커밋하여 At Most Once 보장</li>
 *   <li>에러 발생 시 커밋하지 않아 재처리 가능</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductMetricsConsumer {

    private final ProductMetricsService productMetricsService;
    private final ObjectMapper objectMapper;

    /**
     * like-events 토픽을 구독하여 좋아요 수를 집계합니다.
     *
     * @param records Kafka 메시지 레코드 목록
     * @param acknowledgment 수동 커밋을 위한 Acknowledgment
     */
    @KafkaListener(
        topics = "like-events",
        containerFactory = KafkaConfig.BATCH_LISTENER
    )
    public void consumeLikeEvents(
        List<ConsumerRecord<String, Object>> records,
        Acknowledgment acknowledgment
    ) {
        try {
            for (ConsumerRecord<String, Object> record : records) {
                try {
                    Object value = record.value();
                    
                    // Spring Kafka가 자동으로 역직렬화한 경우
                    if (value instanceof LikeEvent.LikeAdded) {
                        LikeEvent.LikeAdded event = (LikeEvent.LikeAdded) value;
                        productMetricsService.incrementLikeCount(event.productId());
                    } else if (value instanceof LikeEvent.LikeRemoved) {
                        LikeEvent.LikeRemoved event = (LikeEvent.LikeRemoved) value;
                        productMetricsService.decrementLikeCount(event.productId());
                    } else {
                        // JSON 문자열인 경우 수동 파싱
                        LikeEvent.LikeAdded event = parseLikeEvent(value);
                        productMetricsService.incrementLikeCount(event.productId());
                    }
                } catch (Exception e) {
                    log.error("좋아요 이벤트 처리 실패: offset={}, partition={}", 
                        record.offset(), record.partition(), e);
                    // 개별 이벤트 처리 실패는 로그만 기록하고 계속 진행
                }
            }
            
            // 모든 이벤트 처리 완료 후 수동 커밋
            acknowledgment.acknowledge();
            log.debug("좋아요 이벤트 처리 완료: count={}", records.size());
        } catch (Exception e) {
            log.error("좋아요 이벤트 배치 처리 실패: count={}", records.size(), e);
            // 에러 발생 시 커밋하지 않음 (재처리 가능)
            throw e;
        }
    }

    /**
     * order-events 토픽을 구독하여 판매량을 집계합니다.
     *
     * @param records Kafka 메시지 레코드 목록
     * @param acknowledgment 수동 커밋을 위한 Acknowledgment
     */
    @KafkaListener(
        topics = "order-events",
        containerFactory = KafkaConfig.BATCH_LISTENER
    )
    public void consumeOrderEvents(
        List<ConsumerRecord<String, Object>> records,
        Acknowledgment acknowledgment
    ) {
        try {
            for (ConsumerRecord<String, Object> record : records) {
                try {
                    Object value = record.value();
                    OrderEvent.OrderCreated event = parseOrderCreatedEvent(value);
                    
                    // 주문 아이템별로 판매량 집계
                    for (OrderEvent.OrderCreated.OrderItemInfo item : event.orderItems()) {
                        productMetricsService.incrementSalesCount(
                            item.productId(),
                            item.quantity()
                        );
                    }
                } catch (Exception e) {
                    log.error("주문 이벤트 처리 실패: offset={}, partition={}", 
                        record.offset(), record.partition(), e);
                    // 개별 이벤트 처리 실패는 로그만 기록하고 계속 진행
                }
            }
            
            // 모든 이벤트 처리 완료 후 수동 커밋
            acknowledgment.acknowledge();
            log.debug("주문 이벤트 처리 완료: count={}", records.size());
        } catch (Exception e) {
            log.error("주문 이벤트 배치 처리 실패: count={}", records.size(), e);
            // 에러 발생 시 커밋하지 않음 (재처리 가능)
            throw e;
        }
    }

    /**
     * Kafka 메시지 값을 LikeAdded 이벤트로 파싱합니다.
     *
     * @param value Kafka 메시지 값
     * @return 파싱된 LikeAdded 이벤트
     */
    private LikeEvent.LikeAdded parseLikeEvent(Object value) {
        try {
            // JSON 문자열인 경우 파싱
            String json = value instanceof String ? (String) value : objectMapper.writeValueAsString(value);
            return objectMapper.readValue(json, LikeEvent.LikeAdded.class);
        } catch (Exception e) {
            throw new RuntimeException("LikeEvent 파싱 실패", e);
        }
    }

    /**
     * product-events 토픽을 구독하여 조회 수를 집계합니다.
     *
     * @param records Kafka 메시지 레코드 목록
     * @param acknowledgment 수동 커밋을 위한 Acknowledgment
     */
    @KafkaListener(
        topics = "product-events",
        containerFactory = KafkaConfig.BATCH_LISTENER
    )
    public void consumeProductEvents(
        List<ConsumerRecord<String, Object>> records,
        Acknowledgment acknowledgment
    ) {
        try {
            for (ConsumerRecord<String, Object> record : records) {
                try {
                    Object value = record.value();
                    ProductEvent.ProductViewed event = parseProductViewedEvent(value);
                    
                    productMetricsService.incrementViewCount(event.productId());
                } catch (Exception e) {
                    log.error("상품 조회 이벤트 처리 실패: offset={}, partition={}", 
                        record.offset(), record.partition(), e);
                    // 개별 이벤트 처리 실패는 로그만 기록하고 계속 진행
                }
            }
            
            // 모든 이벤트 처리 완료 후 수동 커밋
            acknowledgment.acknowledge();
            log.debug("상품 조회 이벤트 처리 완료: count={}", records.size());
        } catch (Exception e) {
            log.error("상품 조회 이벤트 배치 처리 실패: count={}", records.size(), e);
            // 에러 발생 시 커밋하지 않음 (재처리 가능)
            throw e;
        }
    }

    /**
     * Kafka 메시지 값을 OrderCreated 이벤트로 파싱합니다.
     *
     * @param value Kafka 메시지 값
     * @return 파싱된 OrderCreated 이벤트
     */
    private OrderEvent.OrderCreated parseOrderCreatedEvent(Object value) {
        try {
            if (value instanceof OrderEvent.OrderCreated) {
                return (OrderEvent.OrderCreated) value;
            }
            
            // JSON 문자열인 경우 파싱
            String json = value instanceof String ? (String) value : objectMapper.writeValueAsString(value);
            return objectMapper.readValue(json, OrderEvent.OrderCreated.class);
        } catch (Exception e) {
            throw new RuntimeException("OrderCreated 이벤트 파싱 실패", e);
        }
    }

    /**
     * Kafka 메시지 값을 ProductViewed 이벤트로 파싱합니다.
     *
     * @param value Kafka 메시지 값
     * @return 파싱된 ProductViewed 이벤트
     */
    private ProductEvent.ProductViewed parseProductViewedEvent(Object value) {
        try {
            if (value instanceof ProductEvent.ProductViewed) {
                return (ProductEvent.ProductViewed) value;
            }
            
            // JSON 문자열인 경우 파싱
            String json = value instanceof String ? (String) value : objectMapper.writeValueAsString(value);
            return objectMapper.readValue(json, ProductEvent.ProductViewed.class);
        } catch (Exception e) {
            throw new RuntimeException("ProductViewed 이벤트 파싱 실패", e);
        }
    }
}
