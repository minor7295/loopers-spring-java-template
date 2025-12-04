package com.loopers.infrastructure.paymentgateway;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * PG 결제 게이트웨이 FeignClient (스케줄러 전용).
 * <p>
 * 스케줄러에서 사용하는 조회 API에 Retry를 적용합니다.
 * </p>
 * <p>
 * <b>Retry 정책:</b>
 * <ul>
 *   <li><b>Exponential Backoff 적용:</b> 초기 500ms → 1000ms (최대 5초)</li>
 *   <li><b>최대 재시도 횟수:</b> 3회 (초기 시도 포함)</li>
 *   <li><b>재시도 대상:</b> 5xx 서버 오류, 타임아웃, 네트워크 오류</li>
 * </ul>
 * </p>
 * <p>
 * <b>설계 근거:</b>
 * <ul>
 *   <li><b>비동기/배치 기반:</b> 스케줄러는 배치 작업이므로 Retry가 안전하게 적용 가능</li>
 *   <li><b>일시적 오류 복구:</b> 네트워크 일시적 오류나 PG 서버 일시적 장애 시 자동 복구</li>
 *   <li><b>유저 요청 스레드 점유 없음:</b> 스케줄러 스레드에서 실행되므로 유저 경험에 영향 없음</li>
 * </ul>
 * </p>
 */
@FeignClient(
    name = "paymentGatewaySchedulerClient",
    url = "${payment-gateway.url}",
    path = "/api/v1/payments"
)
public interface PaymentGatewaySchedulerClient {

    /**
     * 결제 정보 확인 (트랜잭션 키로 조회).
     * <p>
     * 스케줄러에서 사용하며, Retry가 적용됩니다.
     * </p>
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
     * <p>
     * 스케줄러에서 사용하며, Retry가 적용됩니다.
     * </p>
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

