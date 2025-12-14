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
import com.loopers.infrastructure.payment.PaymentGatewayClient;
import com.loopers.infrastructure.payment.PaymentGatewayDto;
import com.loopers.infrastructure.payment.PaymentGatewaySchedulerClient;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PurchasingFacade 결제 콜백 및 상태 확인 테스트.
 * <p>
 * PG 결제 콜백 처리 및 상태 확인 API를 통한 복구 로직을 검증합니다.
 * - 콜백 수신 시 주문 상태 업데이트
 * - 콜백 미수신 시 상태 확인 API로 복구
 * - 타임아웃 후 상태 확인 API로 복구
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PurchasingFacade 결제 콜백 및 상태 확인 테스트")
class PurchasingFacadePaymentCallbackTest {

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

    @MockitoBean
    private PaymentGatewaySchedulerClient paymentGatewaySchedulerClient;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
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
    @DisplayName("PG 결제 성공 콜백을 수신하면 주문 상태가 COMPLETED로 변경된다")
    void handlePaymentCallback_successCallback_orderStatusUpdatedToCompleted() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 결제 요청 성공 (트랜잭션 키 반환)
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> paymentResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.TransactionResponse(
                    "TXN123456",
                    PaymentGatewayDto.TransactionStatus.PENDING,
                    null
                )
            );
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenReturn(paymentResponse);

        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111" // 유효한 Luhn 알고리즘 통과 카드 번호
        );
        Long orderId = orderInfo.orderId();

        // 콜백 검증을 위한 PG 조회 API Mock (SUCCESS 상태 반환)
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> pgInquiryResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.OrderResponse(
                    String.format("%06d", orderId),
                    List.of(
                        new PaymentGatewayDto.TransactionResponse(
                            "TXN123456",
                            PaymentGatewayDto.TransactionStatus.SUCCESS,
                            null
                        )
                    )
                )
            );
        when(paymentGatewaySchedulerClient.getTransactionsByOrder(eq(user.getUserId()), eq(String.format("%06d", orderId))))
            .thenReturn(pgInquiryResponse);

        // act
        PaymentGatewayDto.CallbackRequest callbackRequest = new PaymentGatewayDto.CallbackRequest(
            "TXN123456",
            String.format("%06d", orderId),
            PaymentGatewayDto.CardType.SAMSUNG,
            "4111-1111-1111-1111",
            10_000L,
            PaymentGatewayDto.TransactionStatus.SUCCESS,
            null
        );
        purchasingFacade.handlePaymentCallback(orderId, callbackRequest);

        // assert
        Order savedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("PG 결제 실패 콜백을 수신하면 주문 상태가 CANCELED로 변경된다")
    void handlePaymentCallback_failureCallback_orderStatusUpdatedToCanceled() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 결제 요청 성공 (트랜잭션 키 반환)
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> paymentResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.TransactionResponse(
                    "TXN123456",
                    PaymentGatewayDto.TransactionStatus.PENDING,
                    null
                )
            );
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenReturn(paymentResponse);

        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111" // 유효한 Luhn 알고리즘 통과 카드 번호
        );
        Long orderId = orderInfo.orderId();

        // 콜백 검증을 위한 PG 조회 API Mock (FAILED 상태 반환)
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> pgInquiryResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.OrderResponse(
                    String.format("%06d", orderId),
                    List.of(
                        new PaymentGatewayDto.TransactionResponse(
                            "TXN123456",
                            PaymentGatewayDto.TransactionStatus.FAILED,
                            "카드 한도 초과"
                        )
                    )
                )
            );
        when(paymentGatewaySchedulerClient.getTransactionsByOrder(eq(user.getUserId()), eq(String.format("%06d", orderId))))
            .thenReturn(pgInquiryResponse);

        // act
        PaymentGatewayDto.CallbackRequest callbackRequest = new PaymentGatewayDto.CallbackRequest(
            "TXN123456",
            String.format("%06d", orderId),
            PaymentGatewayDto.CardType.SAMSUNG,
            "4111-1111-1111-1111",
            10_000L,
            PaymentGatewayDto.TransactionStatus.FAILED,
            "카드 한도 초과"
        );
        purchasingFacade.handlePaymentCallback(orderId, callbackRequest);

        // assert
        Order savedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CANCELED);
        
        // 재고와 포인트가 원복되었는지 확인
        Product savedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(savedProduct.getStock()).isEqualTo(10);
        
        User savedUser = userRepository.findByUserId(user.getUserId());
        assertThat(savedUser.getPoint().getValue()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("타임아웃 후 상태 확인 API로 주문 상태를 복구할 수 있다")
    void recoverOrderStatus_afterTimeout_statusRecoveredByStatusCheck() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 결제 요청 타임아웃
        String transactionKey = "TXN123456";
        doThrow(new RuntimeException(new java.net.SocketTimeoutException("Request timeout")))
            .when(paymentGatewayClient).requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));

        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111" // 유효한 Luhn 알고리즘 통과 카드 번호
        );
        Long orderId = orderInfo.orderId();

        // 상태 확인 API 응답 (결제 성공) - 주문 ID로 조회
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> orderResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.OrderResponse(
                    String.valueOf(orderId),
                    List.of(
                        new PaymentGatewayDto.TransactionResponse(
                            transactionKey,
                            PaymentGatewayDto.TransactionStatus.SUCCESS,
                            null
                        )
                    )
                )
            );
        when(paymentGatewaySchedulerClient.getTransactionsByOrder(eq(user.getUserId()), eq(String.format("%06d", orderId))))
            .thenReturn(orderResponse);

        // act
        purchasingFacade.recoverOrderStatusByPaymentCheck(user.getUserId(), orderId);

        // assert
        // ✅ EDA 원칙: 결제 타임아웃으로 인해 주문이 취소된 경우,
        // 이후 PG 상태 확인에서 SUCCESS가 반환되더라도 이미 취소된 주문은 복구할 수 없음
        // OrderEventHandler.handlePaymentCompleted에서 취소된 주문을 무시하도록 처리됨
        Order savedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CANCELED);
    }

}

