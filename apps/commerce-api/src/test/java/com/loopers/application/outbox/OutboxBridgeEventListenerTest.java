package com.loopers.application.outbox;

import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.product.ProductEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OutboxBridgeEventListener 테스트.
 */
@ExtendWith(MockitoExtension.class)
class OutboxBridgeEventListenerTest {

    @Mock
    private OutboxEventService outboxEventService;

    @InjectMocks
    private OutboxBridgeEventListener outboxBridgeEventListener;

    @DisplayName("LikeAdded 이벤트를 Outbox에 저장할 수 있다.")
    @Test
    void canHandleLikeAdded() {
        // arrange
        Long userId = 100L;
        Long productId = 1L;
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());

        // act
        outboxBridgeEventListener.handleLikeAdded(event);

        // assert
        verify(outboxEventService).saveEvent(
            "LikeAdded",
            productId.toString(),
            "Product",
            event,
            "like-events",
            productId.toString()
        );
    }

    @DisplayName("LikeRemoved 이벤트를 Outbox에 저장할 수 있다.")
    @Test
    void canHandleLikeRemoved() {
        // arrange
        Long userId = 100L;
        Long productId = 1L;
        LikeEvent.LikeRemoved event = new LikeEvent.LikeRemoved(userId, productId, LocalDateTime.now());

        // act
        outboxBridgeEventListener.handleLikeRemoved(event);

        // assert
        verify(outboxEventService).saveEvent(
            "LikeRemoved",
            productId.toString(),
            "Product",
            event,
            "like-events",
            productId.toString()
        );
    }

    @DisplayName("OrderCreated 이벤트를 Outbox에 저장할 수 있다.")
    @Test
    void canHandleOrderCreated() {
        // arrange
        Long orderId = 1L;
        Long userId = 100L;
        List<OrderEvent.OrderCreated.OrderItemInfo> orderItems = List.of(
            new OrderEvent.OrderCreated.OrderItemInfo(1L, 3)
        );
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            orderId, userId, null, 10000, 0L, orderItems, LocalDateTime.now()
        );

        // act
        outboxBridgeEventListener.handleOrderCreated(event);

        // assert
        verify(outboxEventService).saveEvent(
            "OrderCreated",
            orderId.toString(),
            "Order",
            event,
            "order-events",
            orderId.toString()
        );
    }

    @DisplayName("ProductViewed 이벤트를 Outbox에 저장할 수 있다.")
    @Test
    void canHandleProductViewed() {
        // arrange
        Long productId = 1L;
        Long userId = 100L;
        ProductEvent.ProductViewed event = new ProductEvent.ProductViewed(
            productId, userId, LocalDateTime.now()
        );

        // act
        outboxBridgeEventListener.handleProductViewed(event);

        // assert
        verify(outboxEventService).saveEvent(
            "ProductViewed",
            productId.toString(),
            "Product",
            event,
            "product-events",
            productId.toString()
        );
    }

    @DisplayName("Outbox 저장 실패 시에도 예외를 던지지 않는다 (에러 격리).")
    @Test
    void doesNotThrowException_whenOutboxSaveFails() {
        // arrange
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(100L, 1L, LocalDateTime.now());
        doThrow(new RuntimeException("Outbox 저장 실패"))
            .when(outboxEventService).saveEvent(anyString(), anyString(), anyString(), 
                any(), anyString(), anyString());

        // act & assert - 예외가 발생하지 않아야 함
        outboxBridgeEventListener.handleLikeAdded(event);

        // verify
        verify(outboxEventService).saveEvent(anyString(), anyString(), anyString(), 
            any(), anyString(), anyString());
    }

    @DisplayName("여러 이벤트를 순차적으로 처리할 수 있다.")
    @Test
    void canHandleMultipleEvents() {
        // arrange
        LikeEvent.LikeAdded likeAdded = new LikeEvent.LikeAdded(100L, 1L, LocalDateTime.now());
        LikeEvent.LikeRemoved likeRemoved = new LikeEvent.LikeRemoved(100L, 1L, LocalDateTime.now());
        ProductEvent.ProductViewed productViewed = new ProductEvent.ProductViewed(
            1L, 100L, LocalDateTime.now()
        );

        // act
        outboxBridgeEventListener.handleLikeAdded(likeAdded);
        outboxBridgeEventListener.handleLikeRemoved(likeRemoved);
        outboxBridgeEventListener.handleProductViewed(productViewed);

        // assert
        verify(outboxEventService, times(3)).saveEvent(
            anyString(), anyString(), anyString(), any(), anyString(), anyString()
        );
    }
}
