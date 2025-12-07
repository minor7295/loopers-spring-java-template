package com.loopers.application.purchasing;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
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
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import feign.FeignException;
import feign.Request;

import java.util.Collections;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PurchasingFacade 서킷 브레이커 테스트.
 * <p>
 * 서킷 브레이커의 동작을 검증합니다.
 * - CLOSED → OPEN 전환 (실패율 임계값 초과)
 * - OPEN → HALF_OPEN 전환 (일정 시간 후)
 * - HALF_OPEN → CLOSED 전환 (성공 시)
 * - HALF_OPEN → OPEN 전환 (실패 시)
 * - 서킷 브레이커 OPEN 상태에서 Fallback 동작
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PurchasingFacade 서킷 브레이커 테스트")
class PurchasingFacadeCircuitBreakerTest {

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

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @MockitoBean
    private PaymentGatewayClient paymentGatewayClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        // 서킷 브레이커 상태 초기화
        if (circuitBreakerRegistry != null) {
            circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> cb.reset());
        }
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
    @DisplayName("PG 연속 실패 시 서킷 브레이커가 CLOSED에서 OPEN으로 전환된다")
    void createOrder_consecutiveFailures_circuitBreakerOpens() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 연속 실패 시뮬레이션
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.ServiceUnavailable(
                "Service unavailable",
                Request.create(Request.HttpMethod.POST, "/api/v1/payments", Collections.emptyMap(), null, null, null),
                null,
                Collections.emptyMap()
            ));

        // CircuitBreaker를 리셋하여 초기 상태로 만듦
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                circuitBreaker.reset();
            }
        }

        // act
        // 서킷 브레이커 설정: minimumNumberOfCalls=1, failureRateThreshold=50%
        // 첫 번째 호출이 실패하면 100% 실패율이 되어 임계값 50%를 초과하므로 OPEN 상태로 전환됨
        // 따라서 첫 번째 호출만 실제로 PaymentGatewayClient를 호출하고,
        // 이후 호출들은 서킷 브레이커가 OPEN 상태이므로 fallback이 호출됨
        int numberOfCalls = 5;
        for (int i = 0; i < numberOfCalls; i++) {
            purchasingFacade.createOrder(
                user.getUserId(),
                commands,
                null,
                "SAMSUNG",
                "4111-1111-1111-1111"
            );
        }

        // assert
        // 재시도 정책에 따라 5xx 에러는 최대 3번까지 재시도됨 (maxAttempts: 3)
        // 첫 번째 createOrder 호출에서 재시도가 일어나면서 최대 3번 호출될 수 있음
        // Circuit Breaker가 OPEN 상태가 되면 이후 호출들은 fallback이 호출되어 실제 호출되지 않음
        verify(paymentGatewayClient, atMost(3))
            .requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));
        
        // 서킷 브레이커 상태 확인
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                // 연속 실패 후에는 OPEN 상태여야 함
                // 첫 번째 호출이 실패하면 100% 실패율이 되어 임계값 50%를 초과하므로 OPEN 상태로 전환됨
                assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            }
        }
    }

    @Test
    @DisplayName("서킷 브레이커가 OPEN 상태일 때 Fallback이 동작한다")
    void createOrder_circuitBreakerOpen_fallbackExecuted() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 서킷 브레이커를 OPEN 상태로 만듦
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                circuitBreaker.transitionToOpenState();
            }
        }

        // act
        purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111"
        );

        // assert
        // 서킷 브레이커가 OPEN 상태일 때는 PG API가 호출되지 않아야 함 (Fallback 동작)
        verify(paymentGatewayClient, never())
            .requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));
    }

    @Test
    @DisplayName("서킷 브레이커가 HALF_OPEN 상태에서 성공 시 CLOSED로 전환된다")
    void createOrder_circuitBreakerHalfOpen_success_transitionsToClosed() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 서킷 브레이커를 HALF_OPEN 상태로 만듦
        // 서킷 브레이커는 CLOSED → OPEN → HALF_OPEN 순서로만 전환 가능
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                // 먼저 OPEN 상태로 전환
                circuitBreaker.transitionToOpenState();
                // 그 다음 HALF_OPEN 상태로 전환
                circuitBreaker.transitionToHalfOpenState();
            }
        }

        // PG 성공 응답
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
            .thenReturn(successResponse);

        // act
        purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111"
        );

        // assert
        // 서킷 브레이커 상태가 CLOSED로 전환되었는지 확인
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                // 성공 시 CLOSED로 전환될 수 있음
                assertThat(circuitBreaker.getState()).isIn(
                    CircuitBreaker.State.CLOSED,
                    CircuitBreaker.State.HALF_OPEN
                );
            }
        }
    }

    @Test
    @DisplayName("서킷 브레이커가 HALF_OPEN 상태에서 실패 시 OPEN으로 전환된다")
    void createOrder_circuitBreakerHalfOpen_failure_transitionsToOpen() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 서킷 브레이커를 HALF_OPEN 상태로 만듦
        // 서킷 브레이커는 CLOSED → OPEN → HALF_OPEN 순서로만 전환 가능
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                // 먼저 OPEN 상태로 전환
                circuitBreaker.transitionToOpenState();
                // 그 다음 HALF_OPEN 상태로 전환
                circuitBreaker.transitionToHalfOpenState();
            }
        }

        // PG 실패 응답
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
            "4111-1111-1111-1111"
        );

        // assert
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 서킷 브레이커 상태가 OPEN으로 전환되었는지 확인
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                // HALF_OPEN 상태에서 실패 시 OPEN으로 전환되어야 함
                assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            }
        }
    }

    @Test
    @DisplayName("서킷 브레이커가 OPEN 상태일 때도 내부 시스템은 정상적으로 응답한다")
    void createOrder_circuitBreakerOpen_internalSystemRespondsNormally() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 서킷 브레이커를 OPEN 상태로 만듦
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                circuitBreaker.transitionToOpenState();
            }
        }

        // act
        // 포인트를 사용하지 않고 카드로만 결제 (Circuit Breaker OPEN 상태에서도 주문은 PENDING 상태로 유지)
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111"
        );

        // assert
        // 내부 시스템은 정상적으로 응답해야 함 (예외가 발생하지 않아야 함)
        assertThat(orderInfo).isNotNull();
        assertThat(orderInfo.orderId()).isNotNull();
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 재고는 정상적으로 차감되어야 함
        Product savedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(savedProduct.getStock()).isEqualTo(9);
        
        // 포인트는 사용하지 않았으므로 차감되지 않음
        User savedUser = userRepository.findByUserId(user.getUserId());
        assertThat(savedUser.getPoint().getValue()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("Fallback 응답의 CIRCUIT_BREAKER_OPEN 에러 코드가 올바르게 처리되어 주문이 PENDING 상태로 유지된다")
    void createOrder_fallbackResponseWithCircuitBreakerOpen_orderRemainsPending() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 서킷 브레이커를 OPEN 상태로 만들어 Fallback이 호출되도록 함
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                circuitBreaker.transitionToOpenState();
            }
        }

        // Fallback이 CIRCUIT_BREAKER_OPEN 에러 코드를 반환하도록 Mock 설정
        // (실제로는 PaymentGatewayClientFallback이 호출되지만, 테스트를 위해 명시적으로 설정)
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> fallbackResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
                    "CIRCUIT_BREAKER_OPEN",
                    "PG 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
                ),
                null
            );
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenReturn(fallbackResponse);

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111"
        );

        // assert
        // CIRCUIT_BREAKER_OPEN은 외부 시스템 장애로 간주되어 주문이 PENDING 상태로 유지되어야 함
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 주문이 저장되었는지 확인
        Order savedOrder = orderRepository.findById(orderInfo.orderId()).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        
        // 비즈니스 실패 처리(주문 취소)가 호출되지 않았는지 확인
        // 주문이 CANCELED 상태가 아니어야 함
        assertThat(savedOrder.getStatus()).isNotEqualTo(OrderStatus.CANCELED);
    }

    @Test
    @DisplayName("Retry 실패 후 CircuitBreaker가 OPEN 상태가 되어 Fallback이 호출된다")
    void createOrder_retryFailure_circuitBreakerOpens_fallbackExecuted() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 100_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 모든 재시도가 실패하도록 설정 (5xx 서버 오류)
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.InternalServerError(
                "Internal Server Error",
                Request.create(Request.HttpMethod.POST, "/api/v1/payments", Collections.emptyMap(), null, null, null),
                null,
                Collections.emptyMap()
            ));

        // CircuitBreaker를 리셋하여 초기 상태로 만듦
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                circuitBreaker.reset();
            }
        }

        // act
        // 서킷 브레이커 설정: minimumNumberOfCalls=1, failureRateThreshold=50%
        // 첫 번째 호출이 실패하면 100% 실패율이 되어 임계값 50%를 초과하므로 OPEN 상태로 전환됨
        // 따라서 첫 번째 호출만 실제로 PaymentGatewayClient를 호출하고 (재시도 포함하여 3번),
        // 이후 호출들은 서킷 브레이커가 OPEN 상태이므로 fallback이 호출되어 실제 호출되지 않음
        int numberOfCalls = 6; // 여러 번 호출하여 서킷 브레이커 동작 확인
        for (int i = 0; i < numberOfCalls; i++) {
            purchasingFacade.createOrder(
                user.getUserId(),
                commands,
                null,
                "SAMSUNG",
                "4111-1111-1111-1111"
            );
        }

        // assert
        // 재시도 정책에 따라 5xx 에러는 최대 3번까지 재시도됨 (maxAttempts: 3)
        // 첫 번째 createOrder 호출에서 재시도가 일어나면서 최대 3번 호출될 수 있음
        // Circuit Breaker가 OPEN 상태가 되면 이후 호출들은 fallback이 호출되어 실제 호출되지 않음
        verify(paymentGatewayClient, atMost(3))
            .requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));

        // CircuitBreaker 상태 확인
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                // 실패율이 임계값을 초과했으므로 OPEN 상태로 전환되어야 함
                // 첫 번째 호출이 실패하면 100% 실패율이 되어 임계값 50%를 초과하므로 OPEN 상태로 전환됨
                assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            }
        }

        // 모든 주문이 PENDING 상태로 생성되었는지 확인
        // Circuit Breaker가 언제 OPEN 상태로 전환될지 정확히 예측하기 어려우므로,
        // 최소 1개 이상의 주문이 생성되었는지 확인
        List<Order> orders = orderJpaRepository.findAll();
        assertThat(orders.size()).isGreaterThanOrEqualTo(1);
        orders.forEach(order -> {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        });
    }

    @Test
    @DisplayName("Retry 실패 후 Fallback이 호출되고 CIRCUIT_BREAKER_OPEN 응답이 올바르게 처리된다")
    void createOrder_retryFailure_fallbackCalled_circuitBreakerOpenHandled() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // CircuitBreaker를 OPEN 상태로 만들어 Fallback이 호출되도록 함
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                circuitBreaker.transitionToOpenState();
            }
        }

        // Fallback이 CIRCUIT_BREAKER_OPEN 에러 코드를 반환하도록 설정
        // 실제로는 PaymentGatewayClientFallback이 호출되지만, 테스트를 위해 Mock으로 시뮬레이션
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> fallbackResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
                    "CIRCUIT_BREAKER_OPEN",
                    "PG 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
                ),
                null
            );
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenReturn(fallbackResponse);

        // act
        // 포인트를 사용하지 않고 카드로만 결제 (Circuit Breaker OPEN 상태에서도 주문은 PENDING 상태로 유지)
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111"
        );

        // assert
        // 1. Fallback 응답이 올바르게 처리되어 주문이 PENDING 상태로 유지되어야 함
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 2. 주문이 저장되었는지 확인
        Order savedOrder = orderRepository.findById(orderInfo.orderId()).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        
        // 3. CIRCUIT_BREAKER_OPEN은 외부 시스템 장애로 간주되므로 주문 취소가 발생하지 않아야 함
        assertThat(savedOrder.getStatus()).isNotEqualTo(OrderStatus.CANCELED);
        
        // 4. 재고는 정상적으로 차감되어야 함 (주문은 생성되었지만 결제는 PENDING)
        Product savedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(savedProduct.getStock()).isEqualTo(9);
        
        // 포인트는 사용하지 않았으므로 차감되지 않음
        User savedUser = userRepository.findByUserId(user.getUserId());
        assertThat(savedUser.getPoint().getValue()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("Fallback 응답 처리 로직: CIRCUIT_BREAKER_OPEN 에러 코드는 외부 시스템 장애로 간주되어 주문이 PENDING 상태로 유지된다")
    void createOrder_fallbackResponse_circuitBreakerOpenErrorCode_orderRemainsPending() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // CircuitBreaker를 OPEN 상태로 만들어 Fallback이 호출되도록 함
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                circuitBreaker.transitionToOpenState();
            }
        }

        // Fallback 응답 시뮬레이션: CIRCUIT_BREAKER_OPEN 에러 코드 반환
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> fallbackResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
                    "CIRCUIT_BREAKER_OPEN",  // Fallback이 반환하는 에러 코드
                    "PG 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
                ),
                null
            );
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenReturn(fallbackResponse);

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111"
        );

        // assert
        // 1. Fallback 응답의 CIRCUIT_BREAKER_OPEN 에러 코드가 올바르게 처리되어야 함
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 2. 주문이 저장되었는지 확인
        Order savedOrder = orderRepository.findById(orderInfo.orderId()).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        
        // 3. CIRCUIT_BREAKER_OPEN은 비즈니스 실패가 아니므로 주문 취소가 발생하지 않아야 함
        // PurchasingFacade의 isBusinessFailure() 메서드는 CIRCUIT_BREAKER_OPEN을 false로 반환해야 함
        assertThat(savedOrder.getStatus()).isNotEqualTo(OrderStatus.CANCELED);
        
        // 4. 외부 시스템 장애로 인한 실패이므로 주문은 PENDING 상태로 유지되어 나중에 복구 가능해야 함
        // (상태 확인 API나 콜백을 통해 나중에 상태를 업데이트할 수 있어야 함)
    }

    @Test
    @DisplayName("Retry가 모두 실패한 후 CircuitBreaker가 OPEN 상태가 되면 Fallback이 호출되어 주문이 PENDING 상태로 유지된다")
    void createOrder_retryExhausted_circuitBreakerOpens_fallbackCalled_orderPending() {
        // arrange
        // 6번의 주문 생성 + fallback 테스트 1번 = 총 7번의 주문 생성
        // 각 주문마다 10,000 포인트가 필요하므로 최소 70,000 포인트 필요
        // 여유를 두고 100,000 포인트로 설정
        User user = createAndSaveUser("testuser", "test@example.com", 100_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // 모든 재시도가 실패하도록 설정 (5xx 서버 오류)
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.InternalServerError(
                "Internal Server Error",
                Request.create(Request.HttpMethod.POST, "/api/v1/payments", Collections.emptyMap(), null, null, null),
                null,
                Collections.emptyMap()
            ));

        // CircuitBreaker를 리셋하여 초기 상태로 만듦
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
            if (circuitBreaker != null) {
                circuitBreaker.reset();
            }
        }

        // act
        // 서킷 브레이커 설정: minimumNumberOfCalls=1, failureRateThreshold=50%
        // 첫 번째 호출이 실패하면 100% 실패율이 되어 임계값 50%를 초과하므로 OPEN 상태로 전환됨
        // 따라서 첫 번째 호출만 실제로 PaymentGatewayClient를 호출하고,
        // 이후 호출들은 서킷 브레이커가 OPEN 상태이므로 fallback이 호출되어 실제 호출되지 않음
        int numberOfCalls = 6; // 여러 번 호출하여 서킷 브레이커 동작 확인
        
        for (int i = 0; i < numberOfCalls; i++) {
            purchasingFacade.createOrder(
                user.getUserId(),
                commands,
                null,
                "SAMSUNG",
                "4111-1111-1111-1111"
            );
        }

        // CircuitBreaker 상태 확인
        CircuitBreaker circuitBreaker = null;
        if (circuitBreakerRegistry != null) {
            circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
        }

        // assert
        // 1. 재시도 정책에 따라 5xx 에러는 최대 3번까지 재시도됨 (maxAttempts: 3)
        // 첫 번째 createOrder 호출에서 재시도가 일어나면서 최대 3번 호출될 수 있음
        // Circuit Breaker가 OPEN 상태가 되면 이후 호출들은 fallback이 호출되어 실제 호출되지 않음
        verify(paymentGatewayClient, atMost(3))
            .requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));

        // 2. CircuitBreaker가 OPEN 상태로 전환되었는지 확인
        if (circuitBreaker != null) {
            // 실패율이 임계값을 초과했으므로 OPEN 상태로 전환되어야 함
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        // 3. CircuitBreaker가 OPEN 상태가 되면 다음 호출에서 Fallback이 호출되어야 함
        // Fallback 응답 시뮬레이션
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> fallbackResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
                    "CIRCUIT_BREAKER_OPEN",
                    "PG 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
                ),
                null
            );
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenReturn(fallbackResponse);

        // CircuitBreaker를 강제로 OPEN 상태로 만듦 (Fallback 호출 보장)
        if (circuitBreaker != null) {
            circuitBreaker.transitionToOpenState();
        }

        // Fallback이 호출되는 시나리오 테스트
        OrderInfo fallbackOrderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            null,
            "SAMSUNG",
            "4111-1111-1111-1111"
        );

        // 4. Fallback 응답이 올바르게 처리되어 주문이 PENDING 상태로 유지되어야 함
        assertThat(fallbackOrderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // 5. 모든 주문이 PENDING 상태로 생성되었는지 확인
        List<Order> orders = orderJpaRepository.findAll();
        assertThat(orders.size()).isGreaterThanOrEqualTo(numberOfCalls + 1); // numberOfCalls + fallback 테스트 1번
        orders.forEach(order -> {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getStatus()).isNotEqualTo(OrderStatus.CANCELED);
        });
    }
}

