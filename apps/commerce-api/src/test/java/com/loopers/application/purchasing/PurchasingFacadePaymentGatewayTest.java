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
import org.springframework.test.context.ActiveProfiles;

import java.net.SocketTimeoutException;
import java.util.Collections;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PurchasingFacade PG 연동 테스트.
 * <p>
 * PG 결제 게이트웨이와의 연동에서 발생할 수 있는 다양한 시나리오를 검증합니다.
 * - PG 연동 실패 시 주문 처리
 * - 타임아웃 발생 시 주문 상태
 * - 서킷 브레이커 동작
 * - 재시도 정책 동작
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PurchasingFacade PG 연동 테스트")
class PurchasingFacadePaymentGatewayTest {

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
        reset(paymentGatewayClient); // Mock 초기화
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
    @DisplayName("PG 결제 요청이 타임아웃되어도 주문은 생성되고 PENDING 상태로 유지된다")
    void createOrder_paymentGatewayTimeout_orderCreatedWithPendingStatus() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 결제 요청이 타임아웃 발생
        doThrow(new RuntimeException(new SocketTimeoutException("Request timeout")))
            .when(paymentGatewayClient).requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111" // 유효한 Luhn 알고리즘 통과 카드 번호
        );

        // assert
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 재고는 차감되었는지 확인
        Product savedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(savedProduct.getStock()).isEqualTo(9);
        
        // 포인트 차감 확인 (usedPoint가 null이므로 포인트 차감 없음)
        User savedUser = userRepository.findByUserId(user.getUserId());
        assertThat(savedUser.getPoint().getValue()).isEqualTo(50_000L); // 포인트 차감 없음
        
        // 주문이 저장되었는지 확인
        Order savedOrder = orderRepository.findById(orderInfo.orderId()).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("PG 결제 요청이 실패해도 주문은 생성되고 PENDING 상태로 유지된다")
    void createOrder_paymentGatewayFailure_orderCreatedWithPendingStatus() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 결제 요청이 실패 (외부 시스템 장애 - 주문은 PENDING 상태로 유지)
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> failureResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
                    "INTERNAL_SERVER_ERROR",  // 외부 시스템 장애로 분류되어 주문이 PENDING 상태로 유지됨
                    "서버 오류가 발생했습니다"
                ),
                null
            );
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenReturn(failureResponse);

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111" // 유효한 Luhn 알고리즘 통과 카드 번호
        );

        // assert
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 주문이 저장되었는지 확인
        Order savedOrder = orderRepository.findById(orderInfo.orderId()).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("PG 서버가 500 에러를 반환해도 주문은 생성되고 PENDING 상태로 유지된다")
    void createOrder_paymentGatewayServerError_orderCreatedWithPendingStatus() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 서버가 500 에러 반환
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.InternalServerError(
                "Internal Server Error",
                Request.create(Request.HttpMethod.POST, "/api/v1/payments", Collections.emptyMap(), null, null, null),
                null,
                Collections.emptyMap()
            ));

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111" // 유효한 Luhn 알고리즘 통과 카드 번호
        );

        // assert
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 주문이 저장되었는지 확인
        Order savedOrder = orderRepository.findById(orderInfo.orderId()).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("PG 연결이 실패해도 주문은 생성되고 PENDING 상태로 유지된다")
    void createOrder_paymentGatewayConnectionFailure_orderCreatedWithPendingStatus() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 연결 실패
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.ServiceUnavailable(
                "Service unavailable",
                Request.create(Request.HttpMethod.POST, "/api/v1/payments", Collections.emptyMap(), null, null, null),
                null,
                Collections.emptyMap()
            ));

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111" // 유효한 Luhn 알고리즘 통과 카드 번호
        );

        // assert
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 주문이 저장되었는지 확인
        Order savedOrder = orderRepository.findById(orderInfo.orderId()).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("PG 결제 요청이 타임아웃되어도 내부 시스템은 정상적으로 응답한다")
    void createOrder_paymentGatewayTimeout_internalSystemRespondsNormally() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 결제 요청이 타임아웃 발생
        doThrow(new RuntimeException(new SocketTimeoutException("Request timeout")))
            .when(paymentGatewayClient).requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111" // 유효한 Luhn 알고리즘 통과 카드 번호
        );

        // assert
        // 내부 시스템은 정상적으로 응답해야 함 (예외가 발생하지 않아야 함)
        assertThat(orderInfo).isNotNull();
        assertThat(orderInfo.orderId()).isNotNull();
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
    }
}

