package com.loopers.infrastructure.paymentgateway;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * PG 결제 게이트웨이 FeignClient.
 * <p>
 * CircuitBreaker가 적용되어 있습니다.
 * </p>
 * <p>
 * <b>Retry 정책:</b>
 * <ul>
 *   <li><b>결제 요청 API (requestPayment):</b> Retry 없음 (유저 요청 경로 - 빠른 실패)</li>
 *   <li><b>조회 API (getTransactionsByOrder, getTransaction):</b> Retry 없음 (스케줄러 - 주기적 실행으로 복구)</li>
 * </ul>
 * </p>
 * <p>
 * <b>설계 근거:</b>
 * 실무 권장 패턴에 따라 "실시간 API에서 긴 Retry는 하지 않는다"는 원칙을 따릅니다.
 * 유저 요청 경로에서는 Retry 없이 빠르게 실패하고, 주문은 PENDING 상태로 유지되어
 * 스케줄러에서 주기적으로 상태를 복구합니다.
 * </p>
 */
@FeignClient(
    name = "paymentGatewayClient",
    url = "${payment-gateway.url}",
    path = "/api/v1/payments",
    fallback = PaymentGatewayClientFallback.class
)
public interface PaymentGatewayClient {

    /**
     * 결제 요청.
     *
     * @param userId 사용자 ID (X-USER-ID 헤더)
     * @param request 결제 요청 정보
     * @return 결제 응답
     */
    @PostMapping
    PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> requestPayment(
        @RequestHeader("X-USER-ID") String userId,
        @RequestBody PaymentGatewayDto.PaymentRequest request
    );

    /**
     * 결제 정보 확인 (트랜잭션 키로 조회).
     *
     * @param userId 사용자 ID (X-USER-ID 헤더)
     * @param transactionKey 트랜잭션 키
     * @return 결제 상세 정보
     */
    @GetMapping("/{transactionKey}")
    PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionDetailResponse> getTransaction(
        @RequestHeader("X-USER-ID") String userId,
        @PathVariable("transactionKey") String transactionKey
    );

    /**
     * 주문에 엮인 결제 정보 조회.
     *
     * @param userId 사용자 ID (X-USER-ID 헤더)
     * @param orderId 주문 ID
     * @return 주문별 결제 목록
     */
    @GetMapping
    PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> getTransactionsByOrder(
        @RequestHeader("X-USER-ID") String userId,
        @RequestParam("orderId") String orderId
    );
}

