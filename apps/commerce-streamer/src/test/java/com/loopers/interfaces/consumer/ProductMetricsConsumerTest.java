package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.eventhandled.EventHandledService;
import com.loopers.application.metrics.ProductMetricsService;
import com.loopers.domain.event.LikeEvent;
import com.loopers.domain.event.OrderEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProductMetricsConsumer 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ProductMetricsConsumerTest {

    @Mock
    private ProductMetricsService productMetricsService;

    @Mock
    private EventHandledService eventHandledService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private ProductMetricsConsumer productMetricsConsumer;

    @DisplayName("LikeAdded 이벤트를 처리할 수 있다.")
    @Test
    void canConsumeLikeAddedEvent() {
        // arrange
        String eventId = "test-event-id";
        Long productId = 1L;
        Long userId = 100L;
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("version", "1".getBytes(StandardCharsets.UTF_8)));
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", event, headers
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(productMetricsService).incrementLikeCount(eq(productId), eq(1L));
        verify(eventHandledService).markAsHandled(eventId, "LikeAdded", "like-events");
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("LikeRemoved 이벤트를 처리할 수 있다.")
    @Test
    void canConsumeLikeRemovedEvent() {
        // arrange
        String eventId = "test-event-id-2";
        Long productId = 1L;
        Long userId = 100L;
        LikeEvent.LikeRemoved event = new LikeEvent.LikeRemoved(userId, productId, LocalDateTime.now());
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("version", "2".getBytes(StandardCharsets.UTF_8)));
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", event, headers
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(productMetricsService).decrementLikeCount(eq(productId), eq(2L));
        verify(eventHandledService).markAsHandled(eventId, "LikeRemoved", "like-events");
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("OrderCreated 이벤트를 처리할 수 있다.")
    @Test
    void canConsumeOrderCreatedEvent() {
        // arrange
        String eventId = "test-event-id-3";
        Long orderId = 1L;
        Long userId = 100L;
        Long productId1 = 1L;
        Long productId2 = 2L;
        
        List<OrderEvent.OrderCreated.OrderItemInfo> orderItems = List.of(
            new OrderEvent.OrderCreated.OrderItemInfo(productId1, 3),
            new OrderEvent.OrderCreated.OrderItemInfo(productId2, 2)
        );
        
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            orderId, userId, null, 10000, 0L, orderItems, LocalDateTime.now()
        );
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("version", "3".getBytes(StandardCharsets.UTF_8)));
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "order-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", event, headers
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);

        // act
        productMetricsConsumer.consumeOrderEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(productMetricsService).incrementSalesCount(eq(productId1), eq(3), eq(3L));
        verify(productMetricsService).incrementSalesCount(eq(productId2), eq(2), eq(3L));
        verify(eventHandledService).markAsHandled(eventId, "OrderCreated", "order-events");
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("배치로 여러 이벤트를 처리할 수 있다.")
    @Test
    void canConsumeMultipleEvents() {
        // arrange
        String eventId1 = "test-event-id-4";
        String eventId2 = "test-event-id-5";
        Long productId = 1L;
        Long userId = 100L;
        
        LikeEvent.LikeAdded event1 = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        LikeEvent.LikeRemoved event2 = new LikeEvent.LikeRemoved(userId, productId, LocalDateTime.now());
        
        Headers headers1 = new RecordHeaders();
        headers1.add(new RecordHeader("eventId", eventId1.getBytes(StandardCharsets.UTF_8)));
        headers1.add(new RecordHeader("version", "4".getBytes(StandardCharsets.UTF_8)));
        Headers headers2 = new RecordHeaders();
        headers2.add(new RecordHeader("eventId", eventId2.getBytes(StandardCharsets.UTF_8)));
        headers2.add(new RecordHeader("version", "5".getBytes(StandardCharsets.UTF_8)));
        
        List<ConsumerRecord<String, Object>> records = List.of(
            new ConsumerRecord<>("like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", event1, headers1),
            new ConsumerRecord<>("like-events", 0, 1L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", event2, headers2)
        );

        when(eventHandledService.isAlreadyHandled(eventId1)).thenReturn(false);
        when(eventHandledService.isAlreadyHandled(eventId2)).thenReturn(false);

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId1);
        verify(eventHandledService).isAlreadyHandled(eventId2);
        verify(productMetricsService).incrementLikeCount(eq(productId), eq(4L));
        verify(productMetricsService).decrementLikeCount(eq(productId), eq(5L));
        verify(eventHandledService).markAsHandled(eventId1, "LikeAdded", "like-events");
        verify(eventHandledService).markAsHandled(eventId2, "LikeRemoved", "like-events");
        verify(acknowledgment, times(1)).acknowledge();
    }

    @DisplayName("개별 이벤트 처리 실패 시에도 배치 처리를 계속한다.")
    @Test
    void continuesProcessing_whenIndividualEventFails() {
        // arrange
        String eventId1 = "test-event-id-6";
        String eventId2 = "test-event-id-7";
        Long productId = 1L;
        Long userId = 100L;
        
        LikeEvent.LikeAdded validEvent = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        Object invalidEvent = "invalid-event";
        
        Headers headers1 = new RecordHeaders();
        headers1.add(new RecordHeader("eventId", eventId1.getBytes(StandardCharsets.UTF_8)));
        headers1.add(new RecordHeader("version", "6".getBytes(StandardCharsets.UTF_8)));
        Headers headers2 = new RecordHeaders();
        headers2.add(new RecordHeader("eventId", eventId2.getBytes(StandardCharsets.UTF_8)));
        headers2.add(new RecordHeader("version", "7".getBytes(StandardCharsets.UTF_8)));
        
        when(eventHandledService.isAlreadyHandled(eventId1)).thenReturn(false);
        when(eventHandledService.isAlreadyHandled(eventId2)).thenReturn(false);
        doThrow(new RuntimeException("처리 실패"))
            .when(productMetricsService).incrementLikeCount(any(), anyLong());
        
        List<ConsumerRecord<String, Object>> records = List.of(
            new ConsumerRecord<>("like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", invalidEvent, headers1),
            new ConsumerRecord<>("like-events", 0, 1L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", validEvent, headers2)
        );

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId1);
        verify(eventHandledService).isAlreadyHandled(eventId2);
        verify(productMetricsService, atLeastOnce()).incrementLikeCount(any(), anyLong());
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("개별 이벤트 처리 실패 시에도 acknowledgment를 수행한다.")
    @Test
    void acknowledgesEvenWhenIndividualEventFails() {
        // arrange
        String eventId = "test-event-id-8";
        Long productId = 1L;
        Long userId = 100L;
        
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("version", "8".getBytes(StandardCharsets.UTF_8)));
        
        // 서비스 호출 시 예외 발생
        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);
        doThrow(new RuntimeException("서비스 처리 실패"))
            .when(productMetricsService).incrementLikeCount(eq(productId), anyLong());
        
        List<ConsumerRecord<String, Object>> records = List.of(
            new ConsumerRecord<>("like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", event, headers)
        );

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        // 개별 이벤트 실패는 내부 catch 블록에서 처리되고 계속 진행되므로 acknowledgment는 호출됨
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(productMetricsService).incrementLikeCount(eq(productId), anyLong());
        // 예외 발생 시 markAsHandled는 호출되지 않음
        verify(eventHandledService, never()).markAsHandled(any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("이미 처리된 이벤트는 스킵한다.")
    @Test
    void skipsAlreadyHandledEvent() {
        // arrange
        String eventId = "test-event-id";
        Long productId = 1L;
        Long userId = 100L;
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", event, headers
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(true);

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(productMetricsService, never()).incrementLikeCount(any(), anyLong());
        verify(eventHandledService, never()).markAsHandled(any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("eventId가 없는 메시지는 건너뛴다.")
    @Test
    void skipsEventWithoutEventId() {
        // arrange
        Long productId = 1L;
        Long userId = 100L;
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "like-events", 0, 0L, "key", event
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService, never()).isAlreadyHandled(any());
        verify(productMetricsService, never()).incrementLikeCount(any(), anyLong());
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("동시성 상황에서 DataIntegrityViolationException이 발생하면 정상 처리로 간주한다.")
    @Test
    void handlesDataIntegrityViolationException() {
        // arrange
        String eventId = "test-event-id";
        Long productId = 1L;
        Long userId = 100L;
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("version", "9".getBytes(StandardCharsets.UTF_8)));
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0L, 0, 0, "key", event, headers
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("UNIQUE constraint violation"))
            .when(eventHandledService).markAsHandled(eventId, "LikeAdded", "like-events");

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(productMetricsService).incrementLikeCount(eq(productId), anyLong());
        verify(eventHandledService).markAsHandled(eventId, "LikeAdded", "like-events");
        verify(acknowledgment).acknowledge();
    }
}
