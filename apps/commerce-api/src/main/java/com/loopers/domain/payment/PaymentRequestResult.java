package com.loopers.domain.payment;

/**
 * 결제 요청 결과.
 * <p>
 * PG 결제 요청의 결과를 나타내는 도메인 모델입니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public sealed interface PaymentRequestResult {
    /**
     * 결제 요청 성공.
     *
     * @param transactionKey 트랜잭션 키
     */
    record Success(String transactionKey) implements PaymentRequestResult {}
    
    /**
     * 결제 요청 실패.
     *
     * @param errorCode 오류 코드
     * @param message 오류 메시지
     * @param isTimeout 타임아웃 여부
     * @param isRetryable 재시도 가능 여부
     */
    record Failure(String errorCode, String message, boolean isTimeout, boolean isRetryable) implements PaymentRequestResult {}
}

