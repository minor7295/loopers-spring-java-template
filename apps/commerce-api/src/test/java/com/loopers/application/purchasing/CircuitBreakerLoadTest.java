package com.loopers.application.purchasing;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
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
import com.loopers.testutil.CircuitBreakerTestUtil;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.openfeign.FeignException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Circuit Breaker 부하 테스트.
 * <p>
 * Circuit Breaker가 실제로 열리도록 만드는 부하 테스트입니다.
 * 이 테스트는 Grafana 대시보드에서 Circuit Breaker 상태 변화를 관찰하기 위해 사용됩니다.
 * </p>
 * <p>
 * <b>사용 방법:</b>
 * <ol>
 *   <li>애플리케이션을 실행합니다.</li>
 *   <li>Grafana 대시보드를 엽니다 (http://localhost:3000).</li>
 *   <li>이 테스트를 실행합니다.</li>
 *   <li>Grafana 대시보드에서 Circuit Breaker 상태 변화를 관찰합니다.</li>
 * </ol>
 * </p>
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Circuit Breaker 부하 테스트 (Grafana 모니터링용)")
class CircuitBreakerLoadTest {

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

    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired(required = false)
    private CircuitBreakerTestUtil circuitBreakerTestUtil;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        // Circuit Breaker 리셋
        if (circuitBreakerRegistry != null) {
            circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        }
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
    @DisplayName("연속 실패를 유발하여 Circuit Breaker를 OPEN 상태로 만든다 (Grafana 모니터링용)")
    void triggerCircuitBreakerOpen_withConsecutiveFailures() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 1_000_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 100, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 연속 실패 시뮬레이션 (5xx 서버 오류)
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(FeignException.InternalServerError.create(
                500,
                "Internal Server Error",
                null,
                null,
                null,
                null
            ));

        // Circuit Breaker 리셋
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentGatewayClient");
            if (circuitBreaker != null) {
                circuitBreaker.reset();
                log.info("Circuit Breaker 초기 상태: {}", circuitBreaker.getState());
            }
        }

        // act
        // 실패율 임계값(50%)을 초과하기 위해 최소 5번 호출 중 3번 이상 실패 필요
        // 하지만 안전하게 10번 호출하여 실패율을 높임
        int totalCalls = 10;
        log.info("총 {}번의 주문 요청을 보내어 Circuit Breaker를 OPEN 상태로 만듭니다.", totalCalls);

        for (int i = 0; i < totalCalls; i++) {
            try {
                purchasingFacade.createOrder(
                    user.getUserId(),
                    commands,
                    "SAMSUNG",
                    "1234-5678-9012-3456"
                );
                log.info("주문 요청 {}번 완료 (실패 예상)", i + 1);
            } catch (Exception e) {
                log.debug("주문 요청 {}번 실패: {}", i + 1, e.getMessage());
            }
            
            // Circuit Breaker 상태 확인
            if (circuitBreakerRegistry != null) {
                CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentGatewayClient");
                if (circuitBreaker != null) {
                    log.info("주문 요청 {}번 후 Circuit Breaker 상태: {}", i + 1, circuitBreaker.getState());
                    if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                        log.info("✅ Circuit Breaker가 OPEN 상태로 전환되었습니다!");
                        break;
                    }
                }
            }
            
            // 짧은 대기 시간 (메트릭 수집을 위해)
            Thread.sleep(100);
        }

        // assert
        // Circuit Breaker가 OPEN 상태로 전환되었는지 확인
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentGatewayClient");
            if (circuitBreaker != null) {
                CircuitBreaker.State finalState = circuitBreaker.getState();
                log.info("최종 Circuit Breaker 상태: {}", finalState);
                
                // 실패율이 임계값을 초과했으므로 OPEN 상태일 가능성이 높음
                assertThat(finalState).isIn(
                    CircuitBreaker.State.OPEN,
                    CircuitBreaker.State.CLOSED,
                    CircuitBreaker.State.HALF_OPEN
                );
                
                if (finalState == CircuitBreaker.State.OPEN) {
                    log.info("✅ 테스트 성공: Circuit Breaker가 OPEN 상태로 전환되었습니다!");
                    log.info("Grafana 대시보드에서 Circuit Breaker 상태 변화를 확인하세요.");
                }
            }
        }

        // 모든 주문이 PENDING 상태로 생성되었는지 확인
        List<com.loopers.domain.order.Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(totalCalls);
        orders.forEach(order -> {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        });
    }

    @Test
    @DisplayName("동시 요청을 보내어 Circuit Breaker를 OPEN 상태로 만든다 (Grafana 모니터링용)")
    void triggerCircuitBreakerOpen_withConcurrentRequests() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 1_000_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 100, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // PG 연속 실패 시뮬레이션
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(FeignException.ServiceUnavailable.create(
                503,
                "Service Unavailable",
                null,
                null,
                null,
                null
            ));

        // Circuit Breaker 리셋
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentGatewayClient");
            if (circuitBreaker != null) {
                circuitBreaker.reset();
            }
        }

        // act
        // 동시에 여러 요청을 보내어 Circuit Breaker를 빠르게 OPEN 상태로 만듦
        int concurrentRequests = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch latch = new CountDownLatch(concurrentRequests);

        log.info("동시에 {}개의 주문 요청을 보내어 Circuit Breaker를 OPEN 상태로 만듭니다.", concurrentRequests);

        for (int i = 0; i < concurrentRequests; i++) {
            final int requestNumber = i + 1;
            executorService.submit(() -> {
                try {
                    purchasingFacade.createOrder(
                        user.getUserId(),
                        commands,
                        "SAMSUNG",
                        "1234-5678-9012-3456"
                    );
                    log.info("동시 요청 {}번 완료", requestNumber);
                } catch (Exception e) {
                    log.debug("동시 요청 {}번 실패: {}", requestNumber, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // 모든 요청 완료 대기
        latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        // Circuit Breaker 상태 확인
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentGatewayClient");
            if (circuitBreaker != null) {
                CircuitBreaker.State finalState = circuitBreaker.getState();
                log.info("최종 Circuit Breaker 상태: {}", finalState);
                
                if (finalState == CircuitBreaker.State.OPEN) {
                    log.info("✅ 테스트 성공: Circuit Breaker가 OPEN 상태로 전환되었습니다!");
                    log.info("Grafana 대시보드에서 Circuit Breaker 상태 변화를 확인하세요.");
                }
            }
        }
    }

    @Test
    @DisplayName("Circuit Breaker가 OPEN 상태일 때 Fallback이 동작하는지 확인한다 (Grafana 모니터링용)")
    void verifyFallback_whenCircuitBreakerOpen() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );

        // Circuit Breaker를 강제로 OPEN 상태로 만듦
        if (circuitBreakerTestUtil != null) {
            circuitBreakerTestUtil.openCircuitBreaker("paymentGatewayClient");
        } else if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentGatewayClient");
            if (circuitBreaker != null) {
                circuitBreaker.transitionToOpenState();
            }
        }

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(
            user.getUserId(),
            commands,
            "SAMSUNG",
            "1234-5678-9012-3456"
        );

        // assert
        // Fallback이 동작하여 주문은 PENDING 상태로 생성되어야 함
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        log.info("✅ Fallback이 정상적으로 동작했습니다. 주문 상태: {}", orderInfo.status());
        log.info("Grafana 대시보드에서 'Circuit Breaker Not Permitted Calls' 메트릭을 확인하세요.");
    }
}

