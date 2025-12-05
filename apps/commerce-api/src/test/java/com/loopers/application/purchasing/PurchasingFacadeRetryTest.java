package com.loopers.application.purchasing;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.paymentgateway.PaymentGatewayClient;
import com.loopers.infrastructure.paymentgateway.PaymentGatewayDto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import feign.FeignException;
import feign.Request;

import java.net.SocketTimeoutException;
import java.util.Collections;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PurchasingFacade 재시도 정책 테스트.
 * <p>
 * 재시도 정책의 동작을 검증합니다.
 * - 일시적 오류 발생 시 재시도
 * - 재시도 횟수 제한
 * - 재시도 간격 (backoff)
 * - 최종 실패 시 처리
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PurchasingFacade 재시도 정책 테스트")
class PurchasingFacadeRetryTest {

    @Autowired
    private PurchasingFacade purchasingFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private PaymentGatewayClient paymentGatewayClient;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        reset(paymentGatewayClient);
    }

    private User createAndSaveUser(String userId, String email, long point) {
        User user = User.of(userId, email, "1990-01-01", Gender.MALE, Point.of(point));
        return userRepository.save(user);
    }

    private Brand createAndSaveBrand(String brandName) {
        Brand brand = Brand.of(brandName);
        return brandRepository.save(brand);
    }

    private Product createAndSaveProduct(String productName, int price, int stock, Long brandId) {
        Product product = Product.of(productName, price, stock, brandId);
        return productRepository.save(product);
    }

    @Test
    @DisplayName("PG 4xx 에러는 재시도하지 않는다")
    void createOrder_clientError_noRetry() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 400 에러 (클라이언트 오류)
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.BadRequest(
                "Bad Request",
                Request.create(Request.HttpMethod.POST, "/api/v1/payments", Collections.emptyMap(), null, null, null),
                null,
                Collections.emptyMap()
            ));

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            "SAMSUNG",
            "1234-5678-9012-3456"
        );

        // assert
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 4xx 에러는 재시도하지 않으므로 1번만 호출되어야 함
        verify(paymentGatewayClient, times(1))
            .requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));
    }
}

