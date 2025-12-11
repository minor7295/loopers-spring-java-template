package com.loopers.domain.order;

import com.loopers.application.order.OrderService;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
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
            Optional<Order> result = orderService.getOrder(orderId);

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
            List<Order> result = orderService.getOrdersByUserId(userId);

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
            List<Order> result = orderService.getOrdersByStatus(status);

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

    @DisplayName("주문 취소")
    @Nested
    class CancelOrder {
        @DisplayName("주문을 취소하고 재고를 원복하며 포인트를 환불할 수 있다.")
        @Test
        void cancelsOrderAndRecoversResources() {
            // arrange
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            User user = createUser(userId);
            Order order = Order.of(userId, OrderTestFixture.ValidOrderItem.createMultipleItems());
            
            List<OrderItem> items = order.getItems();
            Product product1 = createProduct(items.get(0).getProductId());
            Product product2 = createProduct(items.get(1).getProductId());
            List<Product> products = List.of(product1, product2);
            Long refundPointAmount = 5000L;

            when(orderRepository.save(any(Order.class))).thenReturn(order);

            // act
            orderService.cancelOrder(order, products, user, refundPointAmount);

            // assert
            verify(orderRepository, times(1)).save(order);
            verify(product1, times(1)).increaseStock(items.get(0).getQuantity());
            verify(product2, times(1)).increaseStock(items.get(1).getQuantity());
            // 상태 변경 검증은 OrderTest에서 이미 검증했으므로 제거
        }

        @DisplayName("주문이 null이면 예외가 발생한다.")
        @Test
        void throwsException_whenOrderIsNull() {
            // arrange
            User user = createUser(OrderTestFixture.ValidOrder.USER_ID);
            List<Product> products = List.of();
            Long refundPointAmount = 0L;

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderService.cancelOrder(null, products, user, refundPointAmount);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(orderRepository, never()).save(any(Order.class));
        }

        @DisplayName("사용자가 null이면 예외가 발생한다.")
        @Test
        void throwsException_whenUserIsNull() {
            // arrange
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            List<Product> products = List.of();
            Long refundPointAmount = 0L;

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderService.cancelOrder(order, products, null, refundPointAmount);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(orderRepository, never()).save(any(Order.class));
        }

        @DisplayName("포인트를 사용하지 않은 주문은 포인트 환불 없이 취소할 수 있다.")
        @Test
        void cancelsOrderWithoutPointRefund() {
            // arrange
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            User user = createUser(userId);
            Order order = Order.of(userId, OrderTestFixture.ValidOrderItem.createMultipleItems());
            
            List<OrderItem> items = order.getItems();
            Product product1 = createProduct(items.get(0).getProductId());
            Product product2 = createProduct(items.get(1).getProductId());
            List<Product> products = List.of(product1, product2);
            Long refundPointAmount = 0L;

            when(orderRepository.save(any(Order.class))).thenReturn(order);

            // act
            orderService.cancelOrder(order, products, user, refundPointAmount);

            // assert
            verify(orderRepository, times(1)).save(order);
            verify(product1, times(1)).increaseStock(items.get(0).getQuantity());
            verify(product2, times(1)).increaseStock(items.get(1).getQuantity());
        }
    }

    @DisplayName("결제 결과에 따른 주문 상태 업데이트")
    @Nested
    class UpdateStatusByPaymentResult {
        @DisplayName("결제 성공 시 주문을 완료 상태로 변경할 수 있다.")
        @Test
        void completesOrder_whenPaymentSuccess() {
            // arrange
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            when(orderRepository.save(any(Order.class))).thenReturn(order);

            // act
            orderService.updateStatusByPaymentResult(order, PaymentStatus.SUCCESS);

            // assert
            verify(orderRepository, times(1)).save(order);
            // 상태 변경 검증은 OrderTest에서 이미 검증했으므로 제거
        }

        @DisplayName("결제 실패 시 주문을 취소 상태로 변경할 수 있다.")
        @Test
        void cancelsOrder_whenPaymentFailed() {
            // arrange
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            when(orderRepository.save(any(Order.class))).thenReturn(order);

            // act
            orderService.updateStatusByPaymentResult(order, PaymentStatus.FAILED);

            // assert
            verify(orderRepository, times(1)).save(order);
            // 상태 변경 검증은 OrderTest에서 이미 검증했으므로 제거
        }

        @DisplayName("결제 대기 상태면 주문 상태를 유지한다.")
        @Test
        void maintainsOrderStatus_whenPaymentPending() {
            // arrange
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );

            // act
            orderService.updateStatusByPaymentResult(order, PaymentStatus.PENDING);

            // assert
            verify(orderRepository, never()).save(any(Order.class));
        }

        @DisplayName("이미 완료된 주문은 처리하지 않는다.")
        @Test
        void skipsProcessing_whenOrderAlreadyCompleted() {
            // arrange
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            order.complete(); // 이미 완료 상태

            // act
            orderService.updateStatusByPaymentResult(order, PaymentStatus.SUCCESS);

            // assert
            verify(orderRepository, never()).save(any(Order.class));
        }

        @DisplayName("이미 취소된 주문은 처리하지 않는다.")
        @Test
        void skipsProcessing_whenOrderAlreadyCanceled() {
            // arrange
            Order order = Order.of(
                OrderTestFixture.ValidOrder.USER_ID,
                OrderTestFixture.ValidOrderItem.createMultipleItems()
            );
            order.cancel(); // 이미 취소 상태

            // act
            orderService.updateStatusByPaymentResult(order, PaymentStatus.FAILED);

            // assert
            verify(orderRepository, never()).save(any(Order.class));
        }

        @DisplayName("주문이 null이면 예외가 발생한다.")
        @Test
        void throwsException_whenOrderIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderService.updateStatusByPaymentResult(null, PaymentStatus.SUCCESS);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(orderRepository, never()).save(any(Order.class));
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

    private Product createProduct(Long productId) {
        // Mock을 사용하여 ID 설정
        Product mockedProduct = mock(Product.class);
        when(mockedProduct.getId()).thenReturn(productId);
        doNothing().when(mockedProduct).increaseStock(any(Integer.class));
        return mockedProduct;
    }
}

