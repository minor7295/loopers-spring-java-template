package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OrderService 테스트.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService")
public class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @DisplayName("주문 저장")
    @Nested
    class SaveOrder {
        @DisplayName("주문을 저장할 수 있다.")
        @Test
        void savesOrder() {
            // arrange
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            when(orderRepository.save(any(Order.class))).thenReturn(order);

            // act
            Order result = orderService.save(order);

            // assert
            assertThat(result).isNotNull();
            verify(orderRepository, times(1)).save(order);
        }
    }

    @DisplayName("주문 조회")
    @Nested
    class FindOrder {
        @DisplayName("주문 ID로 주문을 조회할 수 있다.")
        @Test
        void findsById() {
            // arrange
            Long orderId = 1L;
            Order expectedOrder = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(expectedOrder));

            // act
            Order result = orderService.getById(orderId);

            // assert
            assertThat(result).isEqualTo(expectedOrder);
            verify(orderRepository, times(1)).findById(orderId);
        }

        @DisplayName("주문 ID로 주문을 조회할 수 있다 (Optional 반환).")
        @Test
        void findsByIdOptional() {
            // arrange
            Long orderId = 1L;
            Order expectedOrder = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(expectedOrder));

            // act
            Optional<Order> result = orderService.findById(orderId);

            // assert
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(expectedOrder);
            verify(orderRepository, times(1)).findById(orderId);
        }

        @DisplayName("주문을 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenOrderNotFound() {
            // arrange
            Long orderId = 999L;
            when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderService.getById(orderId);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(orderRepository, times(1)).findById(orderId);
        }

        @DisplayName("사용자 ID로 주문 목록을 조회할 수 있다.")
        @Test
        void findsAllByUserId() {
            // arrange
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            List<Order> expectedOrders = List.of(
                Order.of(userId, OrderTestFixture.ValidOrderItem.createMultipleItems())
            );
            when(orderRepository.findAllByUserId(userId)).thenReturn(expectedOrders);

            // act
            List<Order> result = orderService.findAllByUserId(userId);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result).isEqualTo(expectedOrders);
            verify(orderRepository, times(1)).findAllByUserId(userId);
        }

        @DisplayName("주문 상태로 주문 목록을 조회할 수 있다.")
        @Test
        void findsAllByStatus() {
            // arrange
            OrderStatus status = OrderStatus.PENDING;
            List<Order> expectedOrders = List.of(
                Order.of(OrderTestFixture.ValidOrder.USER_ID, OrderTestFixture.ValidOrderItem.createMultipleItems())
            );
            when(orderRepository.findAllByStatus(status)).thenReturn(expectedOrders);

            // act
            List<Order> result = orderService.findAllByStatus(status);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result).isEqualTo(expectedOrders);
            verify(orderRepository, times(1)).findAllByStatus(status);
        }
    }

    @DisplayName("주문 생성")
    @Nested
    class CreateOrder {
        @DisplayName("주문을 생성할 수 있다 (쿠폰 없음).")
        @Test
        void createsOrder() {
            // arrange
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            List<OrderItem> items = OrderTestFixture.ValidOrderItem.createMultipleItems();
            Order expectedOrder = Order.of(userId, items);
            when(orderRepository.save(any(Order.class))).thenReturn(expectedOrder);

            // act
            Order result = orderService.create(userId, items);

            // assert
            assertThat(result).isNotNull();
            verify(orderRepository, times(1)).save(any(Order.class));
        }

        @DisplayName("주문을 생성할 수 있다 (쿠폰 포함).")
        @Test
        void createsOrderWithCoupon() {
            // arrange
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            List<OrderItem> items = OrderTestFixture.ValidOrderItem.createMultipleItems();
            String couponCode = "COUPON123";
            Integer discountAmount = 1000;
            Order expectedOrder = Order.of(userId, items, couponCode, discountAmount);
            when(orderRepository.save(any(Order.class))).thenReturn(expectedOrder);

            // act
            Order result = orderService.create(userId, items, couponCode, discountAmount);

            // assert
            assertThat(result).isNotNull();
            verify(orderRepository, times(1)).save(any(Order.class));
        }
    }

    @DisplayName("주문 완료")
    @Nested
    class CompleteOrder {
        @DisplayName("주문을 완료 상태로 변경할 수 있다.")
        @Test
        void completesOrder() {
            // arrange
            Long orderId = 1L;
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenReturn(order);

            // act
            Order result = orderService.completeOrder(orderId);

            // assert
            assertThat(result).isNotNull();
            verify(orderRepository, times(1)).findById(orderId);
            verify(orderRepository, times(1)).save(order);
            // 상태 변경 검증은 OrderTest에서 이미 검증했으므로 제거
        }

        @DisplayName("주문을 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenOrderNotFound() {
            // arrange
            Long orderId = 999L;
            when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderService.completeOrder(orderId);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(orderRepository, times(1)).findById(orderId);
            verify(orderRepository, never()).save(any(Order.class));
        }
    }
}

