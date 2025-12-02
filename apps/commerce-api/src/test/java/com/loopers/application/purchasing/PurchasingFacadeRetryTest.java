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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.openfeign.FeignException;
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

    @MockBean
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
    @DisplayName("PG 일시적 오류 발생 시 재시도가 수행된다")
    void createOrder_transientError_retryExecuted() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 첫 번째 호출: 일시적 오류 (500 에러)
        // 두 번째 호출: 성공
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> successResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.TransactionResponse(
                    "TXN123456",
                    PaymentGatewayDto.TransactionStatus.SUCCESS,
                    null
                )
            );

        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(FeignException.InternalServerError.create(
                500,
                "Internal Server Error",
                null,
                null,
                null,
                null
            ))
            .thenReturn(successResponse);

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            "SAMSUNG",
            "1234-5678-9012-3456"
        );

        // assert
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.COMPLETED);
        
        // 재시도가 수행되었는지 확인 (최소 2번 호출)
        verify(paymentGatewayClient, atLeast(2))
            .requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));
    }

    @Test
    @DisplayName("PG 재시도 횟수를 초과하면 최종 실패 처리된다")
    void createOrder_retryExhausted_finalFailureHandled() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 모든 재시도 실패
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(FeignException.InternalServerError.create(
                500,
                "Internal Server Error",
                null,
                null,
                null,
                null
            ));

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            "SAMSUNG",
            "1234-5678-9012-3456"
        );

        // assert
        // 재시도가 모두 실패해도 주문은 PENDING 상태로 생성되어야 함
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 재시도 횟수만큼 호출되었는지 확인
        int maxRetryAttempts = 3; // 설정값에 따라 다를 수 있음
        verify(paymentGatewayClient, times(maxRetryAttempts))
            .requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));
    }

    @Test
    @DisplayName("PG 타임아웃 발생 시 재시도가 수행된다")
    void createOrder_timeout_retryExecuted() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 첫 번째 호출: 타임아웃
        // 두 번째 호출: 성공
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> successResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.TransactionResponse(
                    "TXN123456",
                    PaymentGatewayDto.TransactionStatus.SUCCESS,
                    null
                )
            );

        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.RequestTimeout("Request timeout", null, null, null))
            .thenReturn(successResponse);

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            "SAMSUNG",
            "1234-5678-9012-3456"
        );

        // assert
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.COMPLETED);
        
        // 재시도가 수행되었는지 확인
        verify(paymentGatewayClient, atLeast(2))
            .requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));
    }

    @Test
    @DisplayName("PG 재시도 간격(backoff)이 적용된다")
    void createOrder_retryWithBackoff_backoffApplied() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 첫 번째 호출: 실패
        // 두 번째 호출: 성공
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> successResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.TransactionResponse(
                    "TXN123456",
                    PaymentGatewayDto.TransactionStatus.SUCCESS,
                    null
                )
            );

        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(FeignException.InternalServerError.create(
                500,
                "Internal Server Error",
                null,
                null,
                null,
                null
            ))
            .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            "SAMSUNG",
            "1234-5678-9012-3456"
        );

        long endTime = System.currentTimeMillis();
        long elapsedTime = endTime - startTime;

        // assert
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.COMPLETED);
        
        // 재시도 간격이 적용되었는지 확인 (최소 backoff 시간 이상 소요)
        long minBackoffTime = 100; // 설정값에 따라 다를 수 있음 (ms)
        assertThat(elapsedTime).isGreaterThanOrEqualTo(minBackoffTime);
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
            .thenThrow(FeignException.BadRequest.create(
                400,
                "Bad Request",
                null,
                null,
                null,
                null
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

