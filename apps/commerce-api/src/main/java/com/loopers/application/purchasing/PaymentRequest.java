package com.loopers.application.purchasing;

import com.loopers.infrastructure.paymentgateway.PaymentGatewayDto;

/**
 * 결제 요청 도메인 모델.
 *
 * @author Loopers
 * @version 1.0
 */
public record PaymentRequest(
    String userId,
    String orderId,
    PaymentGatewayDto.CardType cardType,
    String cardNo,
    Long amount,
    String callbackUrl
) {
}

