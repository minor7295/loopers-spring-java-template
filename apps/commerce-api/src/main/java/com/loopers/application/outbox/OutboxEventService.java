package com.loopers.application.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Outbox 이벤트 저장 서비스.
 * <p>
 * 도메인 트랜잭션과 같은 트랜잭션에서 Outbox에 이벤트를 저장합니다.
 * Application 레이어에 위치하여 비즈니스 로직(이벤트 저장 결정)을 처리합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Kafka로 전송할 이벤트를 Outbox에 저장합니다.
     * <p>
     * 도메인 트랜잭션과 같은 트랜잭션에서 실행되어야 합니다.
     * 집계 ID별로 순차적인 버전을 자동으로 부여합니다.
     * </p>
     *
     * @param eventType 이벤트 타입 (예: "OrderCreated", "LikeAdded")
     * @param aggregateId 집계 ID (예: orderId, productId)
     * @param aggregateType 집계 타입 (예: "Order", "Product")
     * @param event 이벤트 객체
     * @param topic Kafka 토픽 이름
     * @param partitionKey 파티션 키
     */
    @Transactional
    public void saveEvent(
        String eventType,
        String aggregateId,
        String aggregateType,
        Object event,
        String topic,
        String partitionKey
    ) {
        try {
            String eventId = UUID.randomUUID().toString();
            String payload = objectMapper.writeValueAsString(event);

            // 집계 ID별 최신 버전 조회 후 +1
            Long latestVersion = outboxEventRepository.findLatestVersionByAggregateId(aggregateId, aggregateType);
            Long nextVersion = latestVersion + 1L;

            OutboxEvent outboxEvent = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .aggregateId(aggregateId)
                .aggregateType(aggregateType)
                .payload(payload)
                .topic(topic)
                .partitionKey(partitionKey)
                .version(nextVersion)
                .build();

            outboxEventRepository.save(outboxEvent);
            log.debug("Outbox 이벤트 저장: eventType={}, aggregateId={}, topic={}, version={}", 
                eventType, aggregateId, topic, nextVersion);
        } catch (Exception e) {
            log.error("Outbox 이벤트 저장 실패: eventType={}, aggregateId={}", 
                eventType, aggregateId, e);
            throw new RuntimeException("Outbox 이벤트 저장 실패", e);
        }
    }
}
