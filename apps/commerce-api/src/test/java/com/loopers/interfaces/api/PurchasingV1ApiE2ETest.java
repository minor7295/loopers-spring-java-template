package com.loopers.interfaces.api;

import com.loopers.application.pointwallet.PointWalletFacade;
import com.loopers.application.signup.SignUpFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.UserTestFixture;
import com.loopers.infrastructure.paymentgateway.PaymentGatewayClient;
import com.loopers.infrastructure.paymentgateway.PaymentGatewayDto;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.purchasing.PurchasingV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import feign.FeignException;
import feign.Request;

import java.net.SocketTimeoutException;
import java.util.Collections;
import org.springframework.core.ParameterizedTypeReference;
import static org.mockito.Mockito.doThrow;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PurchasingV1Api E2E 테스트.
 * <p>
 * PG 연동 관련 E2E 시나리오를 검증합니다.
 * - PG 타임아웃 시나리오
 * - PG 실패 시나리오
 * - 서킷 브레이커 동작
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("PurchasingV1Api E2E 테스트")
public class PurchasingV1ApiE2ETest {

    private static final String ENDPOINT_ORDERS = "/api/v1/orders";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private SignUpFacade signUpFacade;

    @Autowired
    private PointWalletFacade pointWalletFacade;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @MockitoBean
    private PaymentGatewayClient paymentGatewayClient;

    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        reset(paymentGatewayClient);
        // 서킷 브레이커 상태 초기화
        if (circuitBreakerRegistry != null) {
            circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        }
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class CreateOrder {
        @DisplayName("PG 결제 요청이 타임아웃되어도 주문은 생성되고 200 응답을 반환한다")
        @Test
        void returns200_whenPaymentGatewayTimeout() {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;
            signUpFacade.signUp(userId, email, birthDate, Gender.MALE.name());
            pointWalletFacade.chargePoint(userId, 500_000L);

            Brand brand = Brand.of("테스트 브랜드");
            Brand savedBrand = brandRepository.save(brand);
            Product product = Product.of("테스트 상품", 10_000, 10, savedBrand.getId());
            Product savedProduct = productRepository.save(product);

            PurchasingV1Dto.CreateRequest requestBody = new PurchasingV1Dto.CreateRequest(
                List.of(
                    new PurchasingV1Dto.ItemRequest(savedProduct.getId(), 1)
                ),
                new PurchasingV1Dto.PaymentRequest("SAMSUNG", "1234-5678-9012-3456")
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add("X-USER-ID", userId);
            HttpEntity<PurchasingV1Dto.CreateRequest> httpEntity = new HttpEntity<>(requestBody, headers);

            // PG 결제 요청 타임아웃
            doThrow(new RuntimeException(new SocketTimeoutException("Request timeout")))
                .when(paymentGatewayClient).requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class));

            // act
            ParameterizedTypeReference<ApiResponse<PurchasingV1Dto.OrderResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PurchasingV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ENDPOINT_ORDERS, HttpMethod.POST, httpEntity, responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody()).isNotNull(),
                () -> assertThat(response.getBody().data()).isNotNull(),
                () -> assertThat(response.getBody().data().status()).isEqualTo(OrderStatus.PENDING),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS)
            );
        }

        @DisplayName("PG 결제 요청이 실패해도 주문은 생성되고 200 응답을 반환한다")
        @Test
        void returns200_whenPaymentGatewayFailure() {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;
            signUpFacade.signUp(userId, email, birthDate, Gender.MALE.name());
            pointWalletFacade.chargePoint(userId, 500_000L);

            Brand brand = Brand.of("테스트 브랜드");
            Brand savedBrand = brandRepository.save(brand);
            Product product = Product.of("테스트 상품", 10_000, 10, savedBrand.getId());
            Product savedProduct = productRepository.save(product);

            PurchasingV1Dto.CreateRequest requestBody = new PurchasingV1Dto.CreateRequest(
                List.of(
                    new PurchasingV1Dto.ItemRequest(savedProduct.getId(), 1)
                ),
                new PurchasingV1Dto.PaymentRequest("SAMSUNG", "1234-5678-9012-3456")
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add("X-USER-ID", userId);
            HttpEntity<PurchasingV1Dto.CreateRequest> httpEntity = new HttpEntity<>(requestBody, headers);

            // PG 결제 요청 실패 (외부 시스템 장애 시뮬레이션)
            PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> failureResponse =
                new PaymentGatewayDto.ApiResponse<>(
                    new PaymentGatewayDto.ApiResponse.Metadata(
                        PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
                        "INTERNAL_SERVER_ERROR",
                        "PG 서버 내부 오류가 발생했습니다"
                    ),
                    null
                );
            when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
                .thenReturn(failureResponse);

            // act
            ParameterizedTypeReference<ApiResponse<PurchasingV1Dto.OrderResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PurchasingV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ENDPOINT_ORDERS, HttpMethod.POST, httpEntity, responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody()).isNotNull(),
                () -> assertThat(response.getBody().data()).isNotNull(),
                () -> assertThat(response.getBody().data().status()).isEqualTo(OrderStatus.PENDING),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS)
            );
        }

        @DisplayName("PG 결제 요청이 성공하면 주문이 COMPLETED 상태로 생성되고 200 응답을 반환한다")
        @Test
        void returns200_whenPaymentGatewaySuccess() {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;
            signUpFacade.signUp(userId, email, birthDate, Gender.MALE.name());
            pointWalletFacade.chargePoint(userId, 500_000L);

            Brand brand = Brand.of("테스트 브랜드");
            Brand savedBrand = brandRepository.save(brand);
            Product product = Product.of("테스트 상품", 10_000, 10, savedBrand.getId());
            Product savedProduct = productRepository.save(product);

            PurchasingV1Dto.CreateRequest requestBody = new PurchasingV1Dto.CreateRequest(
                List.of(
                    new PurchasingV1Dto.ItemRequest(savedProduct.getId(), 1)
                ),
                new PurchasingV1Dto.PaymentRequest("SAMSUNG", "1234-5678-9012-3456")
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add("X-USER-ID", userId);
            HttpEntity<PurchasingV1Dto.CreateRequest> httpEntity = new HttpEntity<>(requestBody, headers);

            // PG 결제 요청 성공
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
            ParameterizedTypeReference<ApiResponse<PurchasingV1Dto.OrderResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PurchasingV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ENDPOINT_ORDERS, HttpMethod.POST, httpEntity, responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody()).isNotNull(),
                () -> assertThat(response.getBody().data()).isNotNull(),
                () -> assertThat(response.getBody().data().status()).isEqualTo(OrderStatus.COMPLETED),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS)
            );
        }

        @DisplayName("PG 서버가 500 에러를 반환해도 주문은 생성되고 200 응답을 반환한다")
        @Test
        void returns200_whenPaymentGatewayServerError() {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;
            signUpFacade.signUp(userId, email, birthDate, Gender.MALE.name());
            pointWalletFacade.chargePoint(userId, 500_000L);

            Brand brand = Brand.of("테스트 브랜드");
            Brand savedBrand = brandRepository.save(brand);
            Product product = Product.of("테스트 상품", 10_000, 10, savedBrand.getId());
            Product savedProduct = productRepository.save(product);

            PurchasingV1Dto.CreateRequest requestBody = new PurchasingV1Dto.CreateRequest(
                List.of(
                    new PurchasingV1Dto.ItemRequest(savedProduct.getId(), 1)
                ),
                new PurchasingV1Dto.PaymentRequest("SAMSUNG", "1234-5678-9012-3456")
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add("X-USER-ID", userId);
            HttpEntity<PurchasingV1Dto.CreateRequest> httpEntity = new HttpEntity<>(requestBody, headers);

            // 서킷 브레이커를 리셋하여 CLOSED 상태로 시작
            if (circuitBreakerRegistry != null) {
                CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("pgCircuit");
                if (circuitBreaker != null) {
                    circuitBreaker.reset();
                }
            }

            // PG 서버 500 에러
            when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
                .thenThrow(new FeignException.InternalServerError(
                    "Internal Server Error",
                    Request.create(Request.HttpMethod.POST, "/api/v1/payments", Collections.emptyMap(), null, null, null),
                    null,
                    Collections.emptyMap()
                ));

            // act
            ParameterizedTypeReference<ApiResponse<PurchasingV1Dto.OrderResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PurchasingV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ENDPOINT_ORDERS, HttpMethod.POST, httpEntity, responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody()).isNotNull(),
                () -> assertThat(response.getBody().data()).isNotNull(),
                () -> assertThat(response.getBody().data().status()).isEqualTo(OrderStatus.PENDING),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS)
            );
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/callback")
    @Nested
    class HandlePaymentCallback {
        private static final String ENDPOINT_CALLBACK = "/api/v1/orders/{orderId}/callback";

        @DisplayName("PG 결제 성공 콜백을 수신하면 주문 상태가 COMPLETED로 변경되고 200 응답을 반환한다")
        @Test
        void returns200_whenPaymentCallbackSuccess() {
            // arrange
            // TODO: 주문 생성 및 콜백 처리 로직 구현 후 테스트 작성
            // String userId = UserTestFixture.ValidUser.USER_ID;
            // ...
            // HttpEntity<PaymentGatewayDto.CallbackRequest> httpEntity = new HttpEntity<>(callbackRequest, headers);
            // ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(...);
            // assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }

        @DisplayName("PG 결제 실패 콜백을 수신하면 주문 상태가 CANCELED로 변경되고 200 응답을 반환한다")
        @Test
        void returns200_whenPaymentCallbackFailure() {
            // arrange
            // TODO: 주문 생성 및 콜백 처리 로직 구현 후 테스트 작성
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/recover")
    @Nested
    class RecoverOrderStatus {
        private static final String ENDPOINT_RECOVER = "/api/v1/orders/{orderId}/recover";

        @DisplayName("수동으로 주문 상태를 복구할 수 있다")
        @Test
        void returns200_whenOrderStatusRecovered() {
            // arrange
            // TODO: 주문 생성 및 상태 복구 로직 구현 후 테스트 작성
        }
    }
}

