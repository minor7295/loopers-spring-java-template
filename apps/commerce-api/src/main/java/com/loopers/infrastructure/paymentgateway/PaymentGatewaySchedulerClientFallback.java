package com.loopers.infrastructure.paymentgateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PaymentGatewaySchedulerClient의 Fallback 구현.
 * <p>
 * CircuitBreaker가 Open 상태이거나 예외 발생 시 호출됩니다.
 * </p>
 */
@Slf4j
@Component
public class PaymentGatewaySchedulerClientFallback implements PaymentGatewaySchedulerClient {

    @Override
    public PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionDetailResponse> getTransaction(
        String userId,
        String transactionKey
    ) {
        log.warn("PaymentGatewaySchedulerClient Fallback 호출됨. (transactionKey: {}, userId: {})",
            transactionKey, userId);

        // Fallback 응답: 실패 응답 반환
        return new PaymentGatewayDto.ApiResponse<>(
            new PaymentGatewayDto.ApiResponse.Metadata(
                PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
                "CIRCUIT_BREAKER_OPEN",
                "PG 서비스가 일시적으로 사용할 수 없습니다. 다음 스케줄러 실행 시 다시 시도됩니다."
            ),
            null
        );
    }

    @Override
    public PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> getTransactionsByOrder(
        String userId,
        String orderId
    ) {
        log.warn("PaymentGatewaySchedulerClient Fallback 호출됨. (orderId: {}, userId: {})",
            orderId, userId);

        // Fallback 응답: 실패 응답 반환
        return new PaymentGatewayDto.ApiResponse<>(
            new PaymentGatewayDto.ApiResponse.Metadata(
                PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
                "CIRCUIT_BREAKER_OPEN",
                "PG 서비스가 일시적으로 사용할 수 없습니다. 다음 스케줄러 실행 시 다시 시도됩니다."
            ),
            null
        );
    }
}

