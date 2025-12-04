package com.loopers.infrastructure.paymentgateway;

import com.loopers.application.purchasing.PaymentRequest;
import com.loopers.domain.order.PaymentResult;
import feign.FeignException;
import feign.Request;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

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
    
    /**
     * 결제 요청을 전송합니다.
     *
     * @param request 결제 요청
     * @return 결제 결과 (성공 또는 실패)
     */
    public PaymentResult requestPayment(PaymentRequest request) {
        try {
            PaymentGatewayDto.PaymentRequest dtoRequest = toDto(request);
            PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
                paymentGatewayClient.requestPayment(request.userId(), dtoRequest);
            
            return toDomainResult(response, request.orderId());
        } catch (FeignException e) {
            return handleFeignException(e, request.orderId());
        } catch (Exception e) {
            log.error("PG 결제 요청 중 예상치 못한 오류 발생. (orderId: {})", request.orderId(), e);
            return new PaymentResult.Failure(
                "UNKNOWN_ERROR",
                e.getMessage(),
                false,
                false,
                false
            );
        }
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
    
    private PaymentResult handleFeignException(FeignException e, String orderId) {
        Request request = e.request();
        int status = e.status();
        String method = request != null ? request.httpMethod().name() : "UNKNOWN";
        String url = request != null ? request.url() : "UNKNOWN";
        
        boolean isTimeout = isTimeout(e);
        boolean isServerError = status >= 500;
        boolean isClientError = status >= 400 && status < 500;
        
        if (isTimeout) {
            log.error("PG 결제 요청 타임아웃 발생. (orderId: {}, method: {}, url: {})",
                orderId, method, url, e);
            return new PaymentResult.Failure("TIMEOUT", e.getMessage(), true, false, false);
        }
        
        if (isServerError) {
            log.error("PG 서버 오류 발생. (orderId: {}, status: {}, method: {}, url: {})",
                orderId, status, method, url, e);
            return new PaymentResult.Failure(
                String.valueOf(status),
                e.getMessage(),
                false,
                true,
                false
            );
        }
        
        if (isClientError) {
            log.warn("PG 클라이언트 오류 발생. (orderId: {}, status: {}, method: {}, url: {})",
                orderId, status, method, url, e);
            return new PaymentResult.Failure(
                String.valueOf(status),
                e.getMessage(),
                false,
                false,
                true
            );
        }
        
        log.error("PG 결제 요청 중 Feign 예외 발생. (orderId: {}, status: {})",
            orderId, status, e);
        return new PaymentResult.Failure(
            String.valueOf(status),
            e.getMessage(),
            false,
            false,
            false
        );
    }
    
    private boolean isTimeout(FeignException e) {
        Throwable cause = e.getCause();
        return cause instanceof SocketTimeoutException ||
            cause instanceof TimeoutException ||
            (e.getMessage() != null && e.getMessage().contains("timeout"));
    }
}

