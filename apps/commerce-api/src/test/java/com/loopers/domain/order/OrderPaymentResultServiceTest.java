package com.loopers.domain.order;

import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OrderPaymentResultService 테스트.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderPaymentResultService")
public class OrderPaymentResultServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderCancellationService orderCancellationService;

    @InjectMocks
    private OrderPaymentResultService orderPaymentResultService;

    @DisplayName("결제 결과에 따른 주문 처리")
    @Nested
    class ProcessByPaymentResult {
        @DisplayName("결제 성공 시 주문을 완료 상태로 변경할 수 있다.")
        @Test
        void completesOrder_whenPaymentSuccess() {
            // arrange
            Long orderId = 1L;
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            String transactionKey = "TXN123456";

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenReturn(order);

            // act
            boolean result = orderPaymentResultService.updateByPaymentStatus(
                orderId,
                PaymentStatus.SUCCESS,
                transactionKey,
                null
            );

            // assert
            assertThat(result).isTrue();
            verify(orderRepository, times(1)).findById(orderId);
            verify(orderRepository, times(1)).save(order);
            verify(orderCancellationService, never()).cancel(any(), any());
            // 상태 변경 검증은 OrderTest에서 이미 검증했으므로 제거
        }

        @DisplayName("결제 실패 시 주문을 취소 상태로 변경할 수 있다.")
        @Test
        void cancelsOrder_whenPaymentFailed() {
            // arrange
            Long orderId = 1L;
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            Order order = Order.of(userId, OrderTestFixture.ValidOrderItem.createMultipleItems());
            User user = createUser(userId);
            String transactionKey = "TXN123456";
            String reason = "카드 한도 초과";

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(userRepository.findById(userId)).thenReturn(user);
            doNothing().when(orderCancellationService).cancel(any(Order.class), any(User.class));

            // act
            boolean result = orderPaymentResultService.updateByPaymentStatus(
                orderId,
                PaymentStatus.FAILED,
                transactionKey,
                reason
            );

            // assert
            assertThat(result).isTrue();
            verify(orderRepository, times(1)).findById(orderId);
            verify(userRepository, times(1)).findById(userId);
            verify(orderCancellationService, times(1)).cancel(order, user);
        }

        @DisplayName("결제 대기 상태면 주문 상태를 유지한다.")
        @Test
        void maintainsOrderStatus_whenPaymentPending() {
            // arrange
            Long orderId = 1L;
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            String transactionKey = "TXN123456";

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            // act
            boolean result = orderPaymentResultService.updateByPaymentStatus(
                orderId,
                PaymentStatus.PENDING,
                transactionKey,
                null
            );

            // assert
            assertThat(result).isTrue();
            verify(orderRepository, times(1)).findById(orderId);
            verify(orderRepository, never()).save(any(Order.class));
            verify(orderCancellationService, never()).cancel(any(), any());
        }

        @DisplayName("이미 완료된 주문은 처리하지 않는다.")
        @Test
        void skipsProcessing_whenOrderAlreadyCompleted() {
            // arrange
            Long orderId = 1L;
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            order.complete(); // 이미 완료 상태

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            // act
            boolean result = orderPaymentResultService.updateByPaymentStatus(
                orderId,
                PaymentStatus.SUCCESS,
                "TXN123456",
                null
            );

            // assert
            assertThat(result).isTrue();
            verify(orderRepository, times(1)).findById(orderId);
            verify(orderRepository, never()).save(any(Order.class));
        }

        @DisplayName("이미 취소된 주문은 처리하지 않는다.")
        @Test
        void skipsProcessing_whenOrderAlreadyCanceled() {
            // arrange
            Long orderId = 1L;
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            Order order = Order.of(userId, OrderTestFixture.ValidOrderItem.createMultipleItems());
            order.cancel(); // 이미 취소 상태

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            // act
            boolean result = orderPaymentResultService.updateByPaymentStatus(
                orderId,
                PaymentStatus.FAILED,
                "TXN123456",
                "실패 사유"
            );

            // assert
            assertThat(result).isTrue();
            verify(orderRepository, times(1)).findById(orderId);
            verify(orderCancellationService, never()).cancel(any(), any());
        }

        @DisplayName("주문을 찾을 수 없으면 false를 반환한다.")
        @Test
        void returnsFalse_whenOrderNotFound() {
            // arrange
            Long orderId = 999L;
            when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

            // act
            boolean result = orderPaymentResultService.updateByPaymentStatus(
                orderId,
                PaymentStatus.SUCCESS,
                "TXN123456",
                null
            );

            // assert
            assertThat(result).isFalse();
            verify(orderRepository, times(1)).findById(orderId);
            verify(orderRepository, never()).save(any(Order.class));
        }

        @DisplayName("결제 실패 시 사용자를 찾을 수 없으면 false를 반환한다.")
        @Test
        void returnsFalse_whenUserNotFound() {
            // arrange
            Long orderId = 1L;
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            Order order = Order.of(userId, OrderTestFixture.ValidOrderItem.createMultipleItems());

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(userRepository.findById(userId)).thenReturn(null);

            // act
            boolean result = orderPaymentResultService.updateByPaymentStatus(
                orderId,
                PaymentStatus.FAILED,
                "TXN123456",
                "실패 사유"
            );

            // assert
            assertThat(result).isFalse();
            verify(orderRepository, times(1)).findById(orderId);
            verify(userRepository, times(1)).findById(userId);
            verify(orderCancellationService, never()).cancel(any(Order.class), any(User.class));
        }
    }

    private User createUser(Long userId) {
        return User.of(
            String.valueOf(userId),
            "test@example.com",
            "1990-01-01",
            Gender.MALE,
            Point.of(0L)
        );
    }
}

