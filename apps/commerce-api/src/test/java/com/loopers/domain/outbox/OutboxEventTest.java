package com.loopers.domain.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OutboxEvent 도메인 테스트.
 */
class OutboxEventTest {

    @DisplayName("OutboxEvent는 필수 필드로 생성되며 초기 상태가 PENDING이다.")
    @Test
    void createsOutboxEventWithPendingStatus() {
        // arrange
        String eventId = "event-123";
        String eventType = "OrderCreated";
        String aggregateId = "1";
        String aggregateType = "Order";
        String payload = "{\"orderId\":1}";
        String topic = "order-events";
        String partitionKey = "1";

        // act
        OutboxEvent outboxEvent = OutboxEvent.builder()
            .eventId(eventId)
            .eventType(eventType)
            .aggregateId(aggregateId)
            .aggregateType(aggregateType)
            .payload(payload)
            .topic(topic)
            .partitionKey(partitionKey)
            .build();

        // assert
        assertThat(outboxEvent.getEventId()).isEqualTo(eventId);
        assertThat(outboxEvent.getEventType()).isEqualTo(eventType);
        assertThat(outboxEvent.getAggregateId()).isEqualTo(aggregateId);
        assertThat(outboxEvent.getAggregateType()).isEqualTo(aggregateType);
        assertThat(outboxEvent.getPayload()).isEqualTo(payload);
        assertThat(outboxEvent.getTopic()).isEqualTo(topic);
        assertThat(outboxEvent.getPartitionKey()).isEqualTo(partitionKey);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(outboxEvent.getCreatedAt()).isNotNull();
        assertThat(outboxEvent.getPublishedAt()).isNull();
    }

    @DisplayName("이벤트를 발행 완료 상태로 변경할 수 있다.")
    @Test
    void canMarkAsPublished() throws InterruptedException {
        // arrange
        OutboxEvent outboxEvent = OutboxEvent.builder()
            .eventId("event-123")
            .eventType("OrderCreated")
            .aggregateId("1")
            .aggregateType("Order")
            .payload("{}")
            .topic("order-events")
            .partitionKey("1")
            .build();

        LocalDateTime beforePublish = outboxEvent.getCreatedAt();
        Thread.sleep(1); // 시간 차이를 보장하기 위한 작은 지연

        // act
        outboxEvent.markAsPublished();

        // assert
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        assertThat(outboxEvent.getPublishedAt()).isNotNull();
        assertThat(outboxEvent.getPublishedAt()).isAfter(beforePublish);
    }

    @DisplayName("이벤트를 실패 상태로 변경할 수 있다.")
    @Test
    void canMarkAsFailed() {
        // arrange
        OutboxEvent outboxEvent = OutboxEvent.builder()
            .eventId("event-123")
            .eventType("OrderCreated")
            .aggregateId("1")
            .aggregateType("Order")
            .payload("{}")
            .topic("order-events")
            .partitionKey("1")
            .build();

        // act
        outboxEvent.markAsFailed();

        // assert
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.FAILED);
        assertThat(outboxEvent.getPublishedAt()).isNull();
    }

    @DisplayName("발행 완료 후 실패 상태로 변경할 수 있다.")
    @Test
    void canMarkAsFailedAfterPublished() {
        // arrange
        OutboxEvent outboxEvent = OutboxEvent.builder()
            .eventId("event-123")
            .eventType("OrderCreated")
            .aggregateId("1")
            .aggregateType("Order")
            .payload("{}")
            .topic("order-events")
            .partitionKey("1")
            .build();

        outboxEvent.markAsPublished();
        LocalDateTime publishedAt = outboxEvent.getPublishedAt();

        // act
        outboxEvent.markAsFailed();

        // assert
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.FAILED);
        // markAsFailed는 publishedAt을 변경하지 않음
        assertThat(outboxEvent.getPublishedAt()).isEqualTo(publishedAt);
    }
}
