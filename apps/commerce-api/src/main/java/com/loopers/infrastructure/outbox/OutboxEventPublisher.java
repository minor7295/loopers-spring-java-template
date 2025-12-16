package com.loopers.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 이벤트 발행 프로세스.
 * <p>
 * 주기적으로 Outbox에서 발행 대기 중인 이벤트를 읽어 Kafka로 발행합니다.
 * Transactional Outbox Pattern의 Polling 프로세스입니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 발행 대기 중인 Outbox 이벤트를 Kafka로 발행합니다.
     * <p>
     * 1초마다 실행되어 PENDING 상태의 이벤트를 처리합니다.
     * </p>
     */
    @Scheduled(fixedDelay = 1000) // 1초마다 실행
    @Transactional
    public void publishPendingEvents() {
        try {
            List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(BATCH_SIZE);

            if (pendingEvents.isEmpty()) {
                return;
            }

            log.debug("Outbox 이벤트 발행 시작: count={}", pendingEvents.size());

            for (OutboxEvent event : pendingEvents) {
                try {
                    publishEvent(event);
                    event.markAsPublished();
                    outboxEventRepository.save(event);
                    log.debug("Outbox 이벤트 발행 완료: eventId={}, topic={}", 
                        event.getEventId(), event.getTopic());
                } catch (Exception e) {
                    log.error("Outbox 이벤트 발행 실패: eventId={}, topic={}", 
                        event.getEventId(), event.getTopic(), e);
                    event.markAsFailed();
                    outboxEventRepository.save(event);
                    // 개별 이벤트 실패는 계속 진행
                }
            }

            log.debug("Outbox 이벤트 발행 완료: count={}", pendingEvents.size());
        } catch (Exception e) {
            log.error("Outbox 이벤트 발행 프로세스 실패", e);
            // 프로세스 실패는 다음 스케줄에서 재시도
        }
    }

    /**
     * Outbox 이벤트를 Kafka로 발행합니다.
     *
     * @param event 발행할 Outbox 이벤트
     */
    private void publishEvent(OutboxEvent event) {
        try {
            // JSON 문자열을 Map으로 역직렬화하여 Kafka로 전송
            // KafkaTemplate의 JsonSerializer가 자동으로 직렬화합니다
            Object payload = objectMapper.readValue(event.getPayload(), Object.class);
            
            // Kafka로 발행 (비동기)
            kafkaTemplate.send(
                event.getTopic(),
                event.getPartitionKey(),
                payload
            );
            
            log.debug("Outbox 이벤트 Kafka 발행 성공: eventId={}, topic={}, partitionKey={}", 
                event.getEventId(), event.getTopic(), event.getPartitionKey());
        } catch (Exception e) {
            log.error("Kafka 이벤트 발행 실패: eventId={}, topic={}", 
                event.getEventId(), event.getTopic(), e);
            throw new RuntimeException("Kafka 이벤트 발행 실패", e);
        }
    }
}
