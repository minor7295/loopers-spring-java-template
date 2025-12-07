package com.loopers.domain.order;

import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
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
 * OrderCancellationService 테스트.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCancellationService")
public class OrderCancellationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private OrderCancellationService orderCancellationService;

    @DisplayName("주문 취소")
    @Nested
    class CancelOrder {
        @DisplayName("주문을 취소하고 리소스를 원복할 수 있다.")
        @Test
        void cancelsOrderAndRecoversResources() {
            // arrange
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            User user = createUser(userId);
            Order order = Order.of(userId, OrderTestFixture.ValidOrderItem.createMultipleItems());
            
            List<OrderItem> items = order.getItems();
            Product product1 = createProduct(items.get(0).getProductId());
            Product product2 = createProduct(items.get(1).getProductId());

            when(userRepository.findByUserIdForUpdate(user.getUserId())).thenReturn(user);
            when(productRepository.findByIdForUpdate(items.get(0).getProductId()))
                .thenReturn(Optional.of(product1));
            when(productRepository.findByIdForUpdate(items.get(1).getProductId()))
                .thenReturn(Optional.of(product2));
            when(orderRepository.save(any(Order.class))).thenReturn(order);
            when(userRepository.save(any(User.class))).thenReturn(user);
            when(productRepository.save(any(Product.class))).thenReturn(product1, product2);
            // Payment가 없는 경우 (포인트를 사용하지 않은 주문)
            when(paymentService.findByOrderId(any(Long.class))).thenReturn(Optional.empty());

            // act
            orderCancellationService.cancel(order, user);

            // assert
            verify(userRepository, times(1)).findByUserIdForUpdate(user.getUserId());
            verify(productRepository, times(1)).findByIdForUpdate(items.get(0).getProductId());
            verify(productRepository, times(1)).findByIdForUpdate(items.get(1).getProductId());
            verify(orderRepository, times(1)).save(order);
            verify(userRepository, times(1)).save(user);
            verify(productRepository, times(2)).save(any(Product.class));
            // 상태 변경 검증은 OrderTest에서 이미 검증했으므로 제거
        }

        @DisplayName("주문이 null이면 예외가 발생한다.")
        @Test
        void throwsException_whenOrderIsNull() {
            // arrange
            User user = createUser(OrderTestFixture.ValidOrder.USER_ID);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderCancellationService.cancel(null, user);
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

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderCancellationService.cancel(order, null);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(orderRepository, never()).save(any(Order.class));
        }

        @DisplayName("사용자를 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenUserNotFound() {
            // arrange
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            User user = createUser(userId);
            Order order = Order.of(userId, OrderTestFixture.ValidOrderItem.createMultipleItems());

            when(userRepository.findByUserIdForUpdate(user.getUserId())).thenReturn(null);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderCancellationService.cancel(order, user);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(userRepository, times(1)).findByUserIdForUpdate(user.getUserId());
            verify(orderRepository, never()).save(any(Order.class));
        }

        @DisplayName("상품을 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenProductNotFound() {
            // arrange
            Long userId = OrderTestFixture.ValidOrder.USER_ID;
            User user = createUser(userId);
            Order order = Order.of(userId, OrderTestFixture.ValidOrderItem.createMultipleItems());
            List<OrderItem> items = order.getItems();

            when(userRepository.findByUserIdForUpdate(user.getUserId())).thenReturn(user);
            when(productRepository.findByIdForUpdate(items.get(0).getProductId()))
                .thenReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderCancellationService.cancel(order, user);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(userRepository, times(1)).findByUserIdForUpdate(user.getUserId());
            verify(productRepository, times(1)).findByIdForUpdate(items.get(0).getProductId());
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

