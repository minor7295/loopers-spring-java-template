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
 */
@FeignClient(
    name = "paymentGatewayClient",
    url = "${payment-gateway.url}",
    path = "/api/v1/payments"
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

