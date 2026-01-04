package com.loopers.domain.payment;

/**
 * 결제 게이트웨이 인터페이스.
 * <p>
 * 도메인 계층에 정의하여 DIP를 준수합니다.
 * 인프라 계층이 이 인터페이스를 구현합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface PaymentGateway {
    /**
     * PG 결제 요청을 전송합니다.
     *
     * @param request 결제 요청 값 객체
     * @return 결제 요청 결과
     */
    PaymentRequestResult requestPayment(PaymentRequest request);
    
    /**
     * 결제 상태를 조회합니다.
     * <p>
     * <b>주의:</b> userId는 PG 시스템이 요구하는 사용자 식별자(String)입니다.
     * 도메인 모델의 User.id(Long)와는 다른 값입니다.
     * </p>
     *
     * @param userId 사용자 ID (PG 시스템이 요구하는 String 형식의 사용자 식별자)
     * @param orderId 주문 ID
     * @return 결제 상태
     */
    PaymentStatus getPaymentStatus(String userId, Long orderId);
}

