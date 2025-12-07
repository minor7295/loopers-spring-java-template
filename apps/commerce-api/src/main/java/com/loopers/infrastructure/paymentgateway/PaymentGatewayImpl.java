package com.loopers.infrastructure.paymentgateway;

import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentRequestCommand;
import com.loopers.domain.payment.PaymentRequestResult;
import com.loopers.domain.payment.PaymentStatus;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PaymentGateway 인터페이스의 구현체.
 * <p>
 * 도메인 계층의 PaymentGateway 인터페이스를 구현합니다.
 * 인프라 관심사(FeignClient 호출, 예외 처리)를 도메인 모델로 변환합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentGatewayImpl implements PaymentGateway {
    
    private final PaymentGatewayClient paymentGatewayClient;
    private final PaymentGatewaySchedulerClient paymentGatewaySchedulerClient;
    private final PaymentGatewayMetrics metrics;
    
    /**
     * PG 결제 요청을 전송합니다.
     *
     * @param command 결제 요청 명령
     * @return 결제 요청 결과
     */
    @Override
    @CircuitBreaker(name = "pgCircuit", fallbackMethod = "fallback")
    public PaymentRequestResult requestPayment(PaymentRequestCommand command) {
        PaymentGatewayDto.PaymentRequest dtoRequest = toDto(command);
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
            paymentGatewayClient.requestPayment(command.userId(), dtoRequest);
        
        return toDomainResult(response, command.orderId());
    }
    
    /**
     * Circuit Breaker fallback 메서드.
     *
     * @param command 결제 요청 명령
     * @param t 발생한 예외
     * @return 결제 대기 상태의 실패 결과
     */
    public PaymentRequestResult fallback(PaymentRequestCommand command, Throwable t) {
        log.warn("Circuit Breaker fallback 호출됨. (orderId: {}, exception: {})", 
            command.orderId(), t.getClass().getSimpleName(), t);
        metrics.recordFallback("paymentGatewayClient");
        return new PaymentRequestResult.Failure(
            "CIRCUIT_BREAKER_OPEN",
            "결제 대기 상태",
            false,
            false
        );
    }
    
    /**
     * Circuit Breaker fallback 메서드 (결제 상태 조회).
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @param t 발생한 예외
     * @return PENDING 상태 반환
     */
    public PaymentStatus getPaymentStatusFallback(String userId, Long orderId, Throwable t) {
        log.warn("Circuit Breaker fallback 호출됨 (결제 상태 조회). (orderId: {}, exception: {})", 
            orderId, t.getClass().getSimpleName(), t);
        metrics.recordFallback("paymentGatewaySchedulerClient");
        return PaymentStatus.PENDING;
    }
    
    /**
     * 결제 상태를 조회합니다.
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @return 결제 상태 (SUCCESS, FAILED, PENDING)
     */
    @Override
    @CircuitBreaker(name = "pgCircuit", fallbackMethod = "getPaymentStatusFallback")
    public PaymentStatus getPaymentStatus(String userId, Long orderId) {
        // 주문 ID를 6자리 이상 문자열로 변환 (pg-simulator 검증 요구사항)
        String orderIdString = String.format("%06d", orderId);
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
            paymentGatewaySchedulerClient.getTransactionsByOrder(userId, orderIdString);
        
        if (response == null || response.meta() == null
            || response.meta().result() != PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS
            || response.data() == null || response.data().transactions() == null
            || response.data().transactions().isEmpty()) {
            return PaymentStatus.PENDING;
        }
        
        // 가장 최근 트랜잭션의 상태 반환
        PaymentGatewayDto.TransactionResponse latestTransaction =
            response.data().transactions().get(response.data().transactions().size() - 1);
        return convertToPaymentStatus(latestTransaction.status());
    }
    
    private PaymentGatewayDto.PaymentRequest toDto(PaymentRequestCommand command) {
        return new PaymentGatewayDto.PaymentRequest(
            String.format("%06d", command.orderId()),  // 주문 ID를 6자리 이상 문자열로 변환
            PaymentGatewayDto.CardType.valueOf(command.cardType().toUpperCase()),
            command.cardNo(),
            command.amount(),
            command.callbackUrl()
        );
    }
    
    private PaymentRequestResult toDomainResult(
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response,
        Long orderId
    ) {
        if (response != null && response.meta() != null
            && response.meta().result() == PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS
            && response.data() != null) {
            String transactionKey = response.data().transactionKey();
            log.info("PG 결제 요청 성공. (orderId: {}, transactionKey: {})", orderId, transactionKey);
            metrics.recordSuccess("paymentGatewayClient");
            return new PaymentRequestResult.Success(transactionKey);
        } else {
            String errorCode = response != null && response.meta() != null
                ? response.meta().errorCode() : "UNKNOWN";
            String message = response != null && response.meta() != null
                ? response.meta().message() : "응답이 null입니다.";
            log.warn("PG 결제 요청 실패. (orderId: {}, errorCode: {}, message: {})",
                orderId, errorCode, message);
            return new PaymentRequestResult.Failure(errorCode, message, false, false);
        }
    }
    
    private PaymentStatus convertToPaymentStatus(PaymentGatewayDto.TransactionStatus status) {
        return switch (status) {
            case SUCCESS -> PaymentStatus.SUCCESS;
            case FAILED -> PaymentStatus.FAILED;
            case PENDING -> PaymentStatus.PENDING;
        };
    }
    
}

