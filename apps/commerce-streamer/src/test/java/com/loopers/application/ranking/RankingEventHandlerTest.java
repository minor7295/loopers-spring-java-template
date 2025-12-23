package com.loopers.application.ranking;

import com.loopers.domain.event.LikeEvent;
import com.loopers.domain.event.OrderEvent;
import com.loopers.domain.event.ProductEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RankingEventHandler 테스트.
 */
@ExtendWith(MockitoExtension.class)
class RankingEventHandlerTest {

    @Mock
    private RankingService rankingService;

    @InjectMocks
    private RankingEventHandler rankingEventHandler;

    @DisplayName("좋아요 추가 이벤트를 처리할 수 있다.")
    @Test
    void canHandleLikeAdded() {
        // arrange
        Long productId = 1L;
        Long userId = 100L;
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(userId, productId, LocalDateTime.now());

        // act
        rankingEventHandler.handleLikeAdded(event);

        // assert
        verify(rankingService).addLikeScore(eq(productId), any(LocalDate.class), eq(true));
    }

    @DisplayName("좋아요 취소 이벤트를 처리할 수 있다.")
    @Test
    void canHandleLikeRemoved() {
        // arrange
        Long productId = 1L;
        Long userId = 100L;
        LikeEvent.LikeRemoved event = new LikeEvent.LikeRemoved(userId, productId, LocalDateTime.now());

        // act
        rankingEventHandler.handleLikeRemoved(event);

        // assert
        verify(rankingService).addLikeScore(eq(productId), any(LocalDate.class), eq(false));
    }

    @DisplayName("주문 생성 이벤트를 처리할 수 있다.")
    @Test
    void canHandleOrderCreated() {
        // arrange
        Long orderId = 1L;
        Long userId = 100L;
        OrderEvent.OrderCreated.OrderItemInfo item1 = 
            new OrderEvent.OrderCreated.OrderItemInfo(1L, 2);
        OrderEvent.OrderCreated.OrderItemInfo item2 = 
            new OrderEvent.OrderCreated.OrderItemInfo(2L, 3);
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            orderId,
            userId,
            null, // couponCode
            10000, // subtotal
            null, // usedPointAmount
            List.of(item1, item2),
            LocalDateTime.now()
        );

        // act
        rankingEventHandler.handleOrderCreated(event);

        // assert
        // totalQuantity = 2 + 3 = 5
        // averagePrice = 10000 / 5 = 2000
        // item1: 2000 * 2 = 4000
        // item2: 2000 * 3 = 6000
        verify(rankingService).addOrderScore(eq(1L), any(LocalDate.class), eq(4000.0));
        verify(rankingService).addOrderScore(eq(2L), any(LocalDate.class), eq(6000.0));
    }

    @DisplayName("주문 아이템이 없으면 점수를 추가하지 않는다.")
    @Test
    void doesNothing_whenOrderItemsIsEmpty() {
        // arrange
        Long orderId = 1L;
        Long userId = 100L;
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            orderId,
            userId,
            null, // couponCode
            10000, // subtotal
            null, // usedPointAmount
            List.of(),
            LocalDateTime.now()
        );

        // act
        rankingEventHandler.handleOrderCreated(event);

        // assert
        verify(rankingService, never()).addOrderScore(any(), any(), anyDouble());
    }

    @DisplayName("주문 subtotal이 null이면 점수를 추가하지 않는다.")
    @Test
    void doesNothing_whenSubtotalIsNull() {
        // arrange
        Long orderId = 1L;
        Long userId = 100L;
        OrderEvent.OrderCreated.OrderItemInfo item = 
            new OrderEvent.OrderCreated.OrderItemInfo(1L, 2);
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            orderId,
            userId,
            null, // couponCode
            null, // subtotal
            null, // usedPointAmount
            List.of(item),
            LocalDateTime.now()
        );

        // act
        rankingEventHandler.handleOrderCreated(event);

        // assert
        verify(rankingService, never()).addOrderScore(any(), any(), anyDouble());
    }

    @DisplayName("상품 조회 이벤트를 처리할 수 있다.")
    @Test
    void canHandleProductViewed() {
        // arrange
        Long productId = 1L;
        Long userId = 100L;
        ProductEvent.ProductViewed event = new ProductEvent.ProductViewed(productId, userId, LocalDateTime.now());

        // act
        rankingEventHandler.handleProductViewed(event);

        // assert
        verify(rankingService).addViewScore(eq(productId), any(LocalDate.class));
    }
}

