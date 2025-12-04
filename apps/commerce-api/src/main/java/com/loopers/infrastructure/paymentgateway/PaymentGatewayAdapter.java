package com.loopers.infrastructure.paymentgateway;

import com.loopers.application.purchasing.PaymentRequest;
import com.loopers.domain.order.PaymentResult;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 결제 게이트웨이 어댑터.
 * <p>
 * 인프라 관심사(FeignClient 호출, 예외 처리)를 도메인 모델로 변환합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentGatewayAdapter {
    
    private final PaymentGatewayClient paymentGatewayClient;
    private final PaymentGatewaySchedulerClient paymentGatewaySchedulerClient;
    private final PaymentGatewayMetrics metrics;
    
    /**
     * 결제 요청을 전송합니다.
     *
     * @param request 결제 요청
     * @return 결제 결과 (성공 또는 실패)
     */
    @CircuitBreaker(name = "pgCircuit", fallbackMethod = "fallback")
    public PaymentResult requestPayment(PaymentRequest request) {
        PaymentGatewayDto.PaymentRequest dtoRequest = toDto(request);
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
            paymentGatewayClient.requestPayment(request.userId(), dtoRequest);
        
        return toDomainResult(response, request.orderId());
    }
    
    /**
     * Circuit Breaker fallback 메서드.
     *
     * @param request 결제 요청
     * @param t 발생한 예외
     * @return 결제 대기 상태의 실패 결과
     */
    public PaymentResult fallback(PaymentRequest request, Throwable t) {
        log.warn("Circuit Breaker fallback 호출됨. (orderId: {}, exception: {})", 
            request.orderId(), t.getClass().getSimpleName(), t);
        metrics.recordFallback("paymentGatewayClient");
        return new PaymentResult.Failure(
            "CIRCUIT_BREAKER_OPEN",
            "결제 대기 상태",
            false,
            false,
            false
        );
    }
    
    /**
     * 결제 상태를 조회합니다.
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @return 결제 상태 (SUCCESS, FAILED, PENDING)
     */
    public PaymentGatewayDto.TransactionStatus getPaymentStatus(String userId, String orderId) {
        try {
            PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
                paymentGatewaySchedulerClient.getTransactionsByOrder(userId, orderId);
            
            if (response == null || response.meta() == null
                || response.meta().result() != PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS
                || response.data() == null || response.data().transactions() == null
                || response.data().transactions().isEmpty()) {
                return PaymentGatewayDto.TransactionStatus.PENDING;
            }
            
            // 가장 최근 트랜잭션의 상태 반환
            PaymentGatewayDto.TransactionResponse latestTransaction =
                response.data().transactions().get(response.data().transactions().size() - 1);
            return latestTransaction.status();
        } catch (FeignException.NotFound e) {
            // 404 Not Found: 결제 요청이 PG 서버에 전달되지 않았거나 실패한 경우
            // PG 서버 오류로 결제 처리되지 않은 경우이므로 FAILED로 처리하여 주문을 CANCELED로 변경
            log.warn("PG 결제 상태 조회 실패 (404 Not Found). 결제 요청이 PG 서버에 전달되지 않았거나 실패한 것으로 간주합니다. (orderId: {})", orderId);
            metrics.recordClientError("paymentGatewaySchedulerClient", 404);
            return PaymentGatewayDto.TransactionStatus.FAILED;
        } catch (FeignException e) {
            int status = e.status();
            if (status >= 500) {
                metrics.recordServerError("paymentGatewaySchedulerClient", status);
            } else if (status >= 400) {
                metrics.recordClientError("paymentGatewaySchedulerClient", status);
            }
            log.warn("PG 결제 상태 조회 실패. (orderId: {}, status: {})", orderId, status, e);
            return PaymentGatewayDto.TransactionStatus.PENDING;
        } catch (Exception e) {
            log.warn("PG 결제 상태 조회 실패. (orderId: {})", orderId, e);
            return PaymentGatewayDto.TransactionStatus.PENDING;
        }
    }
    
    private PaymentGatewayDto.PaymentRequest toDto(PaymentRequest request) {
        return new PaymentGatewayDto.PaymentRequest(
            request.orderId(),
            request.cardType(),
            request.cardNo(),
            request.amount(),
            request.callbackUrl()
        );
    }
    
    private PaymentResult toDomainResult(
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response,
        String orderId
    ) {
        if (response != null && response.meta() != null
            && response.meta().result() == PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS
            && response.data() != null) {
            String transactionKey = response.data().transactionKey();
            log.info("PG 결제 요청 성공. (orderId: {}, transactionKey: {})", orderId, transactionKey);
            metrics.recordSuccess("paymentGatewayClient");
            return new PaymentResult.Success(transactionKey);
        } else {
            String errorCode = response != null && response.meta() != null
                ? response.meta().errorCode() : "UNKNOWN";
            String message = response != null && response.meta() != null
                ? response.meta().message() : "응답이 null입니다.";
            log.warn("PG 결제 요청 실패. (orderId: {}, errorCode: {}, message: {})",
                orderId, errorCode, message);
            return new PaymentResult.Failure(errorCode, message, false, false, false);
        }
    }
    
}

