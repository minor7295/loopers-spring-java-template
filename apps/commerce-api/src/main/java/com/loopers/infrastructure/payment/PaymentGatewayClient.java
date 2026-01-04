package com.loopers.infrastructure.payment;

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
 * CircuitBreaker, Bulkhead가 적용되어 있습니다.
 * </p>
 * <p>
 * <b>Bulkhead 패턴:</b>
 * <ul>
 *   <li>동시 호출 최대 20개로 제한 (Building Resilient Distributed Systems: 격벽 패턴)</li>
 *   <li>PG 호출 실패가 다른 API에 영향을 주지 않도록 격리</li>
 * </ul>
 * </p>
 * <p>
 * <b>Retry 정책:</b>
 * <ul>
 *   <li><b>결제 요청 API (requestPayment):</b> 5xx 서버 오류만 재시도, 4xx 클라이언트 오류는 재시도하지 않음</li>
 *   <li><b>조회 API (getTransactionsByOrder, getTransaction):</b> Exponential Backoff 적용 (스케줄러 - 비동기/배치 기반 Retry)</li>
 * </ul>
 * </p>
 * <p>
 * <b>설계 근거:</b>
 * <ul>
 *   <li><b>5xx 서버 오류:</b> 일시적 오류이므로 재시도하여 복구 가능</li>
 *   <li><b>4xx 클라이언트 오류:</b> 비즈니스 로직 오류이므로 재시도해도 성공하지 않음</li>
 *   <li><b>Eventually Consistent:</b> 실패 시 주문은 PENDING 상태로 유지되어 스케줄러에서 복구</li>
 * </ul>
 * </p>
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
