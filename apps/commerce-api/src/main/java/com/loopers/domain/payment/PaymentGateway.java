package com.loopers.domain.payment;

import com.loopers.application.payment.PaymentRequestCommand;

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
     * @param command 결제 요청 명령
     * @return 결제 요청 결과
     */
    PaymentRequestResult requestPayment(PaymentRequestCommand command);
    
    /**
     * 결제 상태를 조회합니다.
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @return 결제 상태
     */
    PaymentStatus getPaymentStatus(String userId, Long orderId);
}

