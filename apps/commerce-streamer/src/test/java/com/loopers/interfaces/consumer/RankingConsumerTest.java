package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.eventhandled.EventHandledService;
import com.loopers.application.ranking.RankingService;
import com.loopers.domain.event.LikeEvent;
import com.loopers.domain.event.OrderEvent;
import com.loopers.domain.event.ProductEvent;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RankingConsumer 테스트.
 */
@ExtendWith(MockitoExtension.class)
class RankingConsumerTest {

    @Mock
    private RankingService rankingService;

    @Mock
    private EventHandledService eventHandledService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private RankingConsumer rankingConsumer;

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
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);

        // act
        rankingConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(rankingService).addLikeScore(eq(productId), any(), eq(true));
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
        headers.add(new RecordHeader("eventType", "LikeRemoved".getBytes(StandardCharsets.UTF_8)));
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);

        // act
        rankingConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(rankingService).addLikeScore(eq(productId), any(), eq(false));
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
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "order-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);

        // act
        rankingConsumer.consumeOrderEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        
        // 평균 단가 계산: 10000 / (3 + 2) = 2000
        // productId1: 2000 * 3 = 6000
        // productId2: 2000 * 2 = 4000
        verify(rankingService).addOrderScore(eq(productId1), any(), eq(6000.0));
        verify(rankingService).addOrderScore(eq(productId2), any(), eq(4000.0));
        
        verify(eventHandledService).markAsHandled(eventId, "OrderCreated", "order-events");
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("ProductViewed 이벤트를 처리할 수 있다.")
    @Test
    void canConsumeProductViewedEvent() {
        // arrange
        String eventId = "test-event-id-4";
        Long productId = 1L;
        Long userId = 100L;
        ProductEvent.ProductViewed event = new ProductEvent.ProductViewed(productId, userId, LocalDateTime.now());
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "product-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);

        // act
        rankingConsumer.consumeProductEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(rankingService).addViewScore(eq(productId), any());
        verify(eventHandledService).markAsHandled(eventId, "ProductViewed", "product-events");
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("배치로 여러 이벤트를 처리할 수 있다.")
    @Test
    void canConsumeMultipleEvents() {
        // arrange
        String eventId1 = "test-event-id-5";
        String eventId2 = "test-event-id-6";
        Long productId = 1L;
        Long userId = 100L;
        
        LikeEvent.LikeAdded event1 = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        ProductEvent.ProductViewed event2 = new ProductEvent.ProductViewed(productId, userId, LocalDateTime.now());
        
        Headers headers1 = new RecordHeaders();
        headers1.add(new RecordHeader("eventId", eventId1.getBytes(StandardCharsets.UTF_8)));
        Headers headers2 = new RecordHeaders();
        headers2.add(new RecordHeader("eventId", eventId2.getBytes(StandardCharsets.UTF_8)));
        
        List<ConsumerRecord<String, Object>> records = List.of(
            new ConsumerRecord<>("like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event1, headers1, Optional.empty()),
            new ConsumerRecord<>("product-events", 0, 1L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event2, headers2, Optional.empty())
        );

        when(eventHandledService.isAlreadyHandled(eventId1)).thenReturn(false);
        when(eventHandledService.isAlreadyHandled(eventId2)).thenReturn(false);

        // act
        rankingConsumer.consumeLikeEvents(List.of(records.get(0)), acknowledgment);
        rankingConsumer.consumeProductEvents(List.of(records.get(1)), acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId1);
        verify(eventHandledService).isAlreadyHandled(eventId2);
        verify(rankingService).addLikeScore(eq(productId), any(), eq(true));
        verify(rankingService).addViewScore(eq(productId), any());
        verify(eventHandledService).markAsHandled(eventId1, "LikeAdded", "like-events");
        verify(eventHandledService).markAsHandled(eventId2, "ProductViewed", "product-events");
        verify(acknowledgment, times(2)).acknowledge();
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
            "like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(true);

        // act
        rankingConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(rankingService, never()).addLikeScore(any(), any(), anyBoolean());
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
        rankingConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService, never()).isAlreadyHandled(any());
        verify(rankingService, never()).addLikeScore(any(), any(), anyBoolean());
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("개별 이벤트 처리 실패 시에도 배치 처리를 계속한다.")
    @Test
    void continuesProcessing_whenIndividualEventFails() {
        // arrange
        String eventId1 = "test-event-id-7";
        String eventId2 = "test-event-id-8";
        Long productId = 1L;
        Long userId = 100L;
        
        LikeEvent.LikeAdded validEvent = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        Object invalidEvent = "invalid-event";
        
        Headers headers1 = new RecordHeaders();
        headers1.add(new RecordHeader("eventId", eventId1.getBytes(StandardCharsets.UTF_8)));
        Headers headers2 = new RecordHeaders();
        headers2.add(new RecordHeader("eventId", eventId2.getBytes(StandardCharsets.UTF_8)));
        
        when(eventHandledService.isAlreadyHandled(eventId1)).thenReturn(false);
        when(eventHandledService.isAlreadyHandled(eventId2)).thenReturn(false);
        doThrow(new RuntimeException("처리 실패"))
            .when(rankingService).addLikeScore(any(), any(), anyBoolean());
        
        List<ConsumerRecord<String, Object>> records = List.of(
            new ConsumerRecord<>("like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", invalidEvent, headers1, Optional.empty()),
            new ConsumerRecord<>("like-events", 0, 1L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", validEvent, headers2, Optional.empty())
        );

        // act
        rankingConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId1);
        verify(eventHandledService).isAlreadyHandled(eventId2);
        verify(rankingService, atLeastOnce()).addLikeScore(any(), any(), anyBoolean());
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
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("UNIQUE constraint violation"))
            .when(eventHandledService).markAsHandled(eventId, "LikeAdded", "like-events");

        // act
        rankingConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(rankingService).addLikeScore(eq(productId), any(), eq(true));
        verify(eventHandledService).markAsHandled(eventId, "LikeAdded", "like-events");
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("주문 이벤트에서 totalQuantity가 0이면 점수를 추가하지 않는다.")
    @Test
    void doesNotAddScore_whenTotalQuantityIsZero() {
        // arrange
        String eventId = "test-event-id-9";
        Long orderId = 1L;
        Long userId = 100L;
        
        List<OrderEvent.OrderCreated.OrderItemInfo> orderItems = List.of(
            new OrderEvent.OrderCreated.OrderItemInfo(1L, 0)
        );
        
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            orderId, userId, null, 0, 0L, orderItems, LocalDateTime.now()
        );
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "order-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);

        // act
        rankingConsumer.consumeOrderEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(rankingService, never()).addOrderScore(any(), any(), anyDouble());
        verify(eventHandledService).markAsHandled(eventId, "OrderCreated", "order-events");
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("주문 이벤트에서 subtotal이 null이면 점수를 추가하지 않는다.")
    @Test
    void doesNotAddScore_whenSubtotalIsNull() {
        // arrange
        String eventId = "test-event-id-10";
        Long orderId = 1L;
        Long userId = 100L;
        
        List<OrderEvent.OrderCreated.OrderItemInfo> orderItems = List.of(
            new OrderEvent.OrderCreated.OrderItemInfo(1L, 3)
        );
        
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            orderId, userId, null, null, 0L, orderItems, LocalDateTime.now()
        );
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "order-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        when(eventHandledService.isAlreadyHandled(eventId)).thenReturn(false);

        // act
        rankingConsumer.consumeOrderEvents(records, acknowledgment);

        // assert
        verify(eventHandledService).isAlreadyHandled(eventId);
        verify(rankingService, never()).addOrderScore(any(), any(), anyDouble());
        verify(eventHandledService).markAsHandled(eventId, "OrderCreated", "order-events");
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("중복 메시지 재전송 시 한 번만 처리되어 멱등성이 보장된다.")
    @Test
    void handlesDuplicateMessagesIdempotently() {
        // arrange
        String eventId = "duplicate-event-id";
        Long productId = 1L;
        Long userId = 100L;
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        
        // 동일한 eventId를 가진 메시지 3개 생성
        List<ConsumerRecord<String, Object>> records = List.of(
            new ConsumerRecord<>("like-events", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()),
            new ConsumerRecord<>("like-events", 0, 1L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty()),
            new ConsumerRecord<>("like-events", 0, 2L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", event, headers, Optional.empty())
        );

        // 첫 번째 메시지는 처리되지 않았으므로 false, 나머지는 이미 처리되었으므로 true
        when(eventHandledService.isAlreadyHandled(eventId))
            .thenReturn(false)  // 첫 번째: 처리됨
            .thenReturn(true)    // 두 번째: 이미 처리됨 (스킵)
            .thenReturn(true);   // 세 번째: 이미 처리됨 (스킵)

        // act
        rankingConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        // isAlreadyHandled는 3번 호출됨 (각 메시지마다)
        verify(eventHandledService, times(3)).isAlreadyHandled(eventId);
        
        // addLikeScore는 한 번만 호출되어야 함 (첫 번째 메시지만 처리)
        verify(rankingService, times(1)).addLikeScore(eq(productId), any(), eq(true));
        
        // markAsHandled는 한 번만 호출되어야 함 (첫 번째 메시지만 처리)
        verify(eventHandledService, times(1)).markAsHandled(eventId, "LikeAdded", "like-events");
        
        // acknowledgment는 한 번만 호출되어야 함 (배치 처리 완료)
        verify(acknowledgment, times(1)).acknowledge();
    }
}
