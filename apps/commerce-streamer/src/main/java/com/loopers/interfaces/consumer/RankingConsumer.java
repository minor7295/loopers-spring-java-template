package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.eventhandled.EventHandledService;
import com.loopers.application.ranking.RankingService;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.event.LikeEvent;
import com.loopers.domain.event.OrderEvent;
import com.loopers.domain.event.ProductEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 랭킹 집계 Kafka Consumer.
 * <p>
 * Kafka에서 이벤트를 수취하여 Redis ZSET에 랭킹 점수를 적재합니다.
 * 조회, 좋아요, 주문 이벤트를 기반으로 실시간 랭킹을 구축합니다.
 * </p>
 * <p>
 * <b>처리 이벤트:</b>
 * <ul>
 *   <li><b>like-events:</b> LikeAdded, LikeRemoved (좋아요 점수 집계)</li>
 *   <li><b>order-events:</b> OrderCreated (주문 점수 집계)</li>
 *   <li><b>product-events:</b> ProductViewed (조회 점수 집계)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Manual Ack:</b>
 * <ul>
 *   <li>이벤트 처리 성공 후 수동으로 커밋하여 At Most Once 보장</li>
 *   <li>에러 발생 시 커밋하지 않아 재처리 가능</li>
 * </ul>
 * </p>
 * <p>
 * <b>설계 원칙:</b>
 * <ul>
 *   <li>Eventually Consistent: 일시적인 지연/중복 허용</li>
 *   <li>CQRS Read Model: Write Side(도메인) → Kafka → Read Side(Application) → Redis ZSET</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingConsumer {

    private final RankingService rankingService;
    private final EventHandledService eventHandledService;
    private final ObjectMapper objectMapper;

    private static final String EVENT_ID_HEADER = "eventId";
    private static final String EVENT_TYPE_HEADER = "eventType";
    private static final String VERSION_HEADER = "version";

    /**
     * like-events 토픽을 구독하여 좋아요 점수를 집계합니다.
     * <p>
     * <b>멱등성 처리:</b>
     * <ul>
     *   <li>Kafka 메시지 헤더에서 `eventId`를 추출</li>
     *   <li>이미 처리된 이벤트는 스킵하여 중복 처리 방지</li>
     *   <li>처리 후 `event_handled` 테이블에 기록</li>
     * </ul>
     * </p>
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
                    String eventId = extractEventId(record);
                    if (eventId == null) {
                        log.warn("eventId가 없는 메시지는 건너뜁니다: offset={}, partition={}", 
                            record.offset(), record.partition());
                        continue;
                    }

                    // 멱등성 체크: 이미 처리된 이벤트는 스킵
                    if (eventHandledService.isAlreadyHandled(eventId)) {
                        log.debug("이미 처리된 이벤트 스킵: eventId={}", eventId);
                        continue;
                    }

                    Object value = record.value();
                    String eventType;
                    LocalDate date = LocalDate.now();
                    
                    // Spring Kafka가 자동으로 역직렬화한 경우
                    if (value instanceof LikeEvent.LikeAdded) {
                        LikeEvent.LikeAdded event = (LikeEvent.LikeAdded) value;
                        rankingService.addLikeScore(event.productId(), date, true);
                        eventType = "LikeAdded";
                    } else if (value instanceof LikeEvent.LikeRemoved) {
                        LikeEvent.LikeRemoved event = (LikeEvent.LikeRemoved) value;
                        rankingService.addLikeScore(event.productId(), date, false);
                        eventType = "LikeRemoved";
                    } else {
                        // JSON 문자열인 경우 이벤트 타입 헤더로 구분
                        String eventTypeHeader = extractEventType(record);
                        if ("LikeRemoved".equals(eventTypeHeader)) {
                            LikeEvent.LikeRemoved event = parseLikeRemovedEvent(value);
                            rankingService.addLikeScore(event.productId(), date, false);
                            eventType = "LikeRemoved";
                        } else {
                            // 기본값은 LikeAdded
                            LikeEvent.LikeAdded event = parseLikeEvent(value);
                            rankingService.addLikeScore(event.productId(), date, true);
                            eventType = "LikeAdded";
                        }
                    }

                    // 이벤트 처리 기록 저장
                    eventHandledService.markAsHandled(eventId, eventType, "like-events");
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // UNIQUE 제약조건 위반 = 동시성 상황에서 이미 처리됨 (정상)
                    log.debug("동시성 상황에서 이미 처리된 이벤트: offset={}, partition={}", 
                        record.offset(), record.partition());
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
     * order-events 토픽을 구독하여 주문 점수를 집계합니다.
     * <p>
     * <b>멱등성 처리:</b>
     * <ul>
     *   <li>Kafka 메시지 헤더에서 `eventId`를 추출</li>
     *   <li>이미 처리된 이벤트는 스킵하여 중복 처리 방지</li>
     *   <li>처리 후 `event_handled` 테이블에 기록</li>
     * </ul>
     * </p>
     * <p>
     * <b>주문 금액 계산:</b>
     * <ul>
     *   <li>OrderEvent.OrderCreated에는 개별 상품 가격 정보가 없음</li>
     *   <li>subtotal을 totalQuantity로 나눠서 평균 단가를 구하고, 각 아이템의 quantity를 곱함</li>
     *   <li>향후 개선: 주문 이벤트에 개별 상품 가격 정보 추가</li>
     * </ul>
     * </p>
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
                    String eventId = extractEventId(record);
                    if (eventId == null) {
                        log.warn("eventId가 없는 메시지는 건너뜁니다: offset={}, partition={}", 
                            record.offset(), record.partition());
                        continue;
                    }

                    // 멱등성 체크: 이미 처리된 이벤트는 스킵
                    if (eventHandledService.isAlreadyHandled(eventId)) {
                        log.debug("이미 처리된 이벤트 스킵: eventId={}", eventId);
                        continue;
                    }

                    Object value = record.value();
                    OrderEvent.OrderCreated event = parseOrderCreatedEvent(value);
                    
                    LocalDate date = LocalDate.now();
                    
                    // 주문 아이템별로 점수 집계
                    // 주의: OrderEvent.OrderCreated에는 개별 상품 가격 정보가 없으므로
                    // subtotal을 totalQuantity로 나눠서 평균 단가를 구하고, 각 아이템의 quantity를 곱함
                    int totalQuantity = event.orderItems().stream()
                        .mapToInt(OrderEvent.OrderCreated.OrderItemInfo::quantity)
                        .sum();
                    
                    if (totalQuantity > 0 && event.subtotal() != null) {
                        double averagePrice = (double) event.subtotal() / totalQuantity;
                        
                        for (OrderEvent.OrderCreated.OrderItemInfo item : event.orderItems()) {
                            double orderAmount = averagePrice * item.quantity();
                            rankingService.addOrderScore(item.productId(), date, orderAmount);
                        }
                    }

                    // 이벤트 처리 기록 저장
                    eventHandledService.markAsHandled(eventId, "OrderCreated", "order-events");
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // UNIQUE 제약조건 위반 = 동시성 상황에서 이미 처리됨 (정상)
                    log.debug("동시성 상황에서 이미 처리된 이벤트: offset={}, partition={}", 
                        record.offset(), record.partition());
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
     * product-events 토픽을 구독하여 조회 점수를 집계합니다.
     * <p>
     * <b>멱등성 처리:</b>
     * <ul>
     *   <li>Kafka 메시지 헤더에서 `eventId`를 추출</li>
     *   <li>이미 처리된 이벤트는 스킵하여 중복 처리 방지</li>
     *   <li>처리 후 `event_handled` 테이블에 기록</li>
     * </ul>
     * </p>
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
                    String eventId = extractEventId(record);
                    if (eventId == null) {
                        log.warn("eventId가 없는 메시지는 건너뜁니다: offset={}, partition={}", 
                            record.offset(), record.partition());
                        continue;
                    }

                    // 멱등성 체크: 이미 처리된 이벤트는 스킵
                    if (eventHandledService.isAlreadyHandled(eventId)) {
                        log.debug("이미 처리된 이벤트 스킵: eventId={}", eventId);
                        continue;
                    }

                    Object value = record.value();
                    ProductEvent.ProductViewed event = parseProductViewedEvent(value);
                    
                    LocalDate date = LocalDate.now();
                    
                    rankingService.addViewScore(event.productId(), date);

                    // 이벤트 처리 기록 저장
                    eventHandledService.markAsHandled(eventId, "ProductViewed", "product-events");
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // UNIQUE 제약조건 위반 = 동시성 상황에서 이미 처리됨 (정상)
                    log.debug("동시성 상황에서 이미 처리된 이벤트: offset={}, partition={}", 
                        record.offset(), record.partition());
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
            throw new RuntimeException("LikeAdded 이벤트 파싱 실패", e);
        }
    }

    /**
     * Kafka 메시지 값을 LikeRemoved 이벤트로 파싱합니다.
     *
     * @param value Kafka 메시지 값
     * @return 파싱된 LikeRemoved 이벤트
     */
    private LikeEvent.LikeRemoved parseLikeRemovedEvent(Object value) {
        try {
            // JSON 문자열인 경우 파싱
            String json = value instanceof String ? (String) value : objectMapper.writeValueAsString(value);
            return objectMapper.readValue(json, LikeEvent.LikeRemoved.class);
        } catch (Exception e) {
            throw new RuntimeException("LikeRemoved 이벤트 파싱 실패", e);
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

    /**
     * Kafka 메시지 헤더에서 eventId를 추출합니다.
     *
     * @param record Kafka 메시지 레코드
     * @return eventId (없으면 null)
     */
    private String extractEventId(ConsumerRecord<String, Object> record) {
        Header header = record.headers().lastHeader(EVENT_ID_HEADER);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Kafka 메시지 헤더에서 eventType을 추출합니다.
     *
     * @param record Kafka 메시지 레코드
     * @return eventType (없으면 null)
     */
    private String extractEventType(ConsumerRecord<String, Object> record) {
        Header header = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Kafka 메시지 헤더에서 version을 추출합니다.
     *
     * @param record Kafka 메시지 레코드
     * @return version (없으면 null)
     */
    private Long extractVersion(ConsumerRecord<String, Object> record) {
        Header header = record.headers().lastHeader(VERSION_HEADER);
        if (header != null && header.value() != null) {
            try {
                String versionStr = new String(header.value(), StandardCharsets.UTF_8);
                return Long.parseLong(versionStr);
            } catch (NumberFormatException e) {
                log.warn("버전 헤더 파싱 실패: offset={}, partition={}", 
                    record.offset(), record.partition());
                return null;
            }
        }
        return null;
    }
}
