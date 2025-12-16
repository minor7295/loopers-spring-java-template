package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.metrics.ProductMetricsService;
import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.order.OrderEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ProductMetricsConsumer 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ProductMetricsConsumerTest {

    @Mock
    private ProductMetricsService productMetricsService;

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
        verify(productMetricsService).incrementLikeCount(productId);
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("LikeRemoved 이벤트를 처리할 수 있다.")
    @Test
    void canConsumeLikeRemovedEvent() {
        // arrange
        Long productId = 1L;
        Long userId = 100L;
        LikeEvent.LikeRemoved event = new LikeEvent.LikeRemoved(userId, productId, LocalDateTime.now());
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "like-events", 0, 0L, "key", event
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(productMetricsService).decrementLikeCount(productId);
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("OrderCreated 이벤트를 처리할 수 있다.")
    @Test
    void canConsumeOrderCreatedEvent() {
        // arrange
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
        
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "order-events", 0, 0L, "key", event
        );
        List<ConsumerRecord<String, Object>> records = List.of(record);

        // act
        productMetricsConsumer.consumeOrderEvents(records, acknowledgment);

        // assert
        verify(productMetricsService).incrementSalesCount(productId1, 3);
        verify(productMetricsService).incrementSalesCount(productId2, 2);
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("배치로 여러 이벤트를 처리할 수 있다.")
    @Test
    void canConsumeMultipleEvents() {
        // arrange
        Long productId = 1L;
        Long userId = 100L;
        
        LikeEvent.LikeAdded event1 = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        LikeEvent.LikeRemoved event2 = new LikeEvent.LikeRemoved(userId, productId, LocalDateTime.now());
        
        List<ConsumerRecord<String, Object>> records = List.of(
            new ConsumerRecord<>("like-events", 0, 0L, "key", event1),
            new ConsumerRecord<>("like-events", 0, 1L, "key", event2)
        );

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(productMetricsService).incrementLikeCount(productId);
        verify(productMetricsService).decrementLikeCount(productId);
        verify(acknowledgment, times(1)).acknowledge();
    }

    @DisplayName("개별 이벤트 처리 실패 시에도 배치 처리를 계속한다.")
    @Test
    void continuesProcessing_whenIndividualEventFails() {
        // arrange
        Long productId = 1L;
        Long userId = 100L;
        
        LikeEvent.LikeAdded validEvent = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        Object invalidEvent = "invalid-event";
        
        doThrow(new RuntimeException("처리 실패"))
            .when(productMetricsService).incrementLikeCount(any());
        
        List<ConsumerRecord<String, Object>> records = List.of(
            new ConsumerRecord<>("like-events", 0, 0L, "key", invalidEvent),
            new ConsumerRecord<>("like-events", 0, 1L, "key", validEvent)
        );

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        verify(productMetricsService, atLeastOnce()).incrementLikeCount(any());
        verify(acknowledgment).acknowledge();
    }

    @DisplayName("개별 이벤트 처리 실패 시에도 acknowledgment를 수행한다.")
    @Test
    void acknowledgesEvenWhenIndividualEventFails() {
        // arrange
        Long productId = 1L;
        Long userId = 100L;
        
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());
        
        // 서비스 호출 시 예외 발생
        doThrow(new RuntimeException("서비스 처리 실패"))
            .when(productMetricsService).incrementLikeCount(productId);
        
        List<ConsumerRecord<String, Object>> records = List.of(
            new ConsumerRecord<>("like-events", 0, 0L, "key", event)
        );

        // act
        productMetricsConsumer.consumeLikeEvents(records, acknowledgment);

        // assert
        // 개별 이벤트 실패는 내부에서 처리되고 계속 진행되므로 acknowledgment는 호출됨
        verify(productMetricsService).incrementLikeCount(productId);
        verify(acknowledgment).acknowledge();
    }
}
