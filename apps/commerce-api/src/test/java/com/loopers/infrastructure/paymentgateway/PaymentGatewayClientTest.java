package com.loopers.infrastructure.paymentgateway;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.openfeign.FeignException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PaymentGatewayClient 타임아웃 및 실패 처리 테스트.
 * <p>
 * 외부 PG 시스템과의 통신에서 발생할 수 있는 다양한 장애 시나리오를 검증합니다.
 * - 타임아웃 처리
 * - 네트워크 오류 처리
 * - 서버 오류 처리
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PaymentGatewayClient 타임아웃 및 실패 처리 테스트")
class PaymentGatewayClientTest {

    @MockBean
    private PaymentGatewayClient paymentGatewayClient;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        reset(paymentGatewayClient);
    }

    @Test
    @DisplayName("PG 결제 요청 시 타임아웃이 발생하면 적절한 예외가 발생한다")
    void requestPayment_timeout_throwsException() {
        // arrange
        String userId = "testuser";
        PaymentGatewayDto.PaymentRequest request = new PaymentGatewayDto.PaymentRequest(
            "ORDER001",
            PaymentGatewayDto.CardType.SAMSUNG,
            "1234-5678-9012-3456",
            10_000L,
            "http://localhost:8080/api/v1/orders/1/callback"
        );

        // Mock 서버에서 타임아웃 예외 발생
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.RequestTimeout("Request timeout", null, null, null));

        // act & assert
        assertThatThrownBy(() -> paymentGatewayClient.requestPayment(userId, request))
            .isInstanceOf(FeignException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("PG 결제 요청 시 연결 타임아웃이 발생하면 적절한 예외가 발생한다")
    void requestPayment_connectionTimeout_throwsException() {
        // arrange
        String userId = "testuser";
        PaymentGatewayDto.PaymentRequest request = new PaymentGatewayDto.PaymentRequest(
            "ORDER001",
            PaymentGatewayDto.CardType.SAMSUNG,
            "1234-5678-9012-3456",
            10_000L,
            "http://localhost:8080/api/v1/orders/1/callback"
        );

        // Mock 서버에서 연결 실패 예외 발생
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.ConnectTimeout("Connection timeout", null, null, null));

        // act & assert
        assertThatThrownBy(() -> paymentGatewayClient.requestPayment(userId, request))
            .isInstanceOf(FeignException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("PG 결제 요청 시 읽기 타임아웃이 발생하면 적절한 예외가 발생한다")
    void requestPayment_readTimeout_throwsException() {
        // arrange
        String userId = "testuser";
        PaymentGatewayDto.PaymentRequest request = new PaymentGatewayDto.PaymentRequest(
            "ORDER001",
            PaymentGatewayDto.CardType.SAMSUNG,
            "1234-5678-9012-3456",
            10_000L,
            "http://localhost:8080/api/v1/orders/1/callback"
        );

        // Mock 서버에서 읽기 타임아웃 예외 발생
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(new FeignException.RequestTimeout("Read timed out", null, null, null));

        // act & assert
        assertThatThrownBy(() -> paymentGatewayClient.requestPayment(userId, request))
            .isInstanceOf(FeignException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("PG 결제 상태 확인 API 호출 시 타임아웃이 발생하면 적절한 예외가 발생한다")
    void getTransaction_timeout_throwsException() {
        // arrange
        String userId = "testuser";
        String transactionKey = "TXN123456";

        // Mock 서버에서 타임아웃 예외 발생
        when(paymentGatewayClient.getTransaction(anyString(), anyString()))
            .thenThrow(new FeignException.RequestTimeout("Request timeout", null, null, null));

        // act & assert
        assertThatThrownBy(() -> paymentGatewayClient.getTransaction(userId, transactionKey))
            .isInstanceOf(FeignException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("PG 서버가 500 에러를 반환하면 적절한 예외가 발생한다")
    void requestPayment_serverError_throwsException() {
        // arrange
        String userId = "testuser";
        PaymentGatewayDto.PaymentRequest request = new PaymentGatewayDto.PaymentRequest(
            "ORDER001",
            PaymentGatewayDto.CardType.SAMSUNG,
            "1234-5678-9012-3456",
            10_000L,
            "http://localhost:8080/api/v1/orders/1/callback"
        );

        // Mock 서버에서 500 에러 반환
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(FeignException.InternalServerError.create(
                500,
                "Internal Server Error",
                null,
                null,
                null,
                null
            ));

        // act & assert
        assertThatThrownBy(() -> paymentGatewayClient.requestPayment(userId, request))
            .isInstanceOf(FeignException.class)
            .matches(e -> ((FeignException) e).status() == 500);
    }

    @Test
    @DisplayName("PG 서버가 400 에러를 반환하면 적절한 예외가 발생한다")
    void requestPayment_badRequest_throwsException() {
        // arrange
        String userId = "testuser";
        PaymentGatewayDto.PaymentRequest request = new PaymentGatewayDto.PaymentRequest(
            "ORDER001",
            PaymentGatewayDto.CardType.SAMSUNG,
            "INVALID_CARD", // 잘못된 카드 번호
            10_000L,
            "http://localhost:8080/api/v1/orders/1/callback"
        );

        // Mock 서버에서 400 에러 반환
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenThrow(FeignException.BadRequest.create(
                400,
                "Bad Request",
                null,
                null,
                null,
                null
            ));

        // act & assert
        assertThatThrownBy(() -> paymentGatewayClient.requestPayment(userId, request))
            .isInstanceOf(FeignException.class)
            .matches(e -> ((FeignException) e).status() == 400);
    }

    @Test
    @DisplayName("PG 결제 요청이 성공하면 정상적인 응답을 받는다")
    void requestPayment_success_returnsResponse() {
        // arrange
        String userId = "testuser";
        PaymentGatewayDto.PaymentRequest request = new PaymentGatewayDto.PaymentRequest(
            "ORDER001",
            PaymentGatewayDto.CardType.SAMSUNG,
            "1234-5678-9012-3456",
            10_000L,
            "http://localhost:8080/api/v1/orders/1/callback"
        );

        // Mock 서버에서 성공 응답 반환
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> successResponse =
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
            .thenReturn(successResponse);

        // act
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
            paymentGatewayClient.requestPayment(userId, request);

        // assert
        assertThat(response.meta().result()).isEqualTo(PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS);
        assertThat(response.data()).isNotNull();
        assertThat(response.data().transactionKey()).isNotNull();
    }

    @Test
    @DisplayName("PG 결제 상태 확인 API가 성공하면 정상적인 응답을 받는다")
    void getTransaction_success_returnsResponse() {
        // arrange
        String userId = "testuser";
        String transactionKey = "TXN123456";

        // Mock 서버에서 성공 응답 반환
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionDetailResponse> successResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.TransactionDetailResponse(
                    transactionKey,
                    "ORDER001",
                    PaymentGatewayDto.CardType.SAMSUNG,
                    "1234-5678-9012-3456",
                    10_000L,
                    PaymentGatewayDto.TransactionStatus.SUCCESS,
                    null
                )
            );
        when(paymentGatewayClient.getTransaction(anyString(), anyString()))
            .thenReturn(successResponse);

        // act
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionDetailResponse> response =
            paymentGatewayClient.getTransaction(userId, transactionKey);

        // assert
        assertThat(response.meta().result()).isEqualTo(PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS);
        assertThat(response.data()).isNotNull();
        assertThat(response.data().transactionKey()).isEqualTo(transactionKey);
    }
}

