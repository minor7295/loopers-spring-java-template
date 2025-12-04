package com.loopers.application.purchasing;

import com.loopers.infrastructure.paymentgateway.PaymentGatewayDto;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 결제 요청 빌더.
 * <p>
 * 결제 요청 도메인 모델을 생성합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class PaymentRequestBuilder {
    
    @Value("${server.port:8080}")
    private int serverPort;
    
    /**
     * 결제 요청을 생성합니다.
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @param cardType 카드 타입 문자열
     * @param cardNo 카드 번호
     * @param amount 결제 금액
     * @return 결제 요청 도메인 모델
     * @throws CoreException 잘못된 카드 타입인 경우
     */
    public PaymentRequest build(String userId, Long orderId, String cardType, String cardNo, Integer amount) {
        return new PaymentRequest(
            userId,
            String.valueOf(orderId),
            parseCardType(cardType),
            cardNo,
            amount.longValue(),
            generateCallbackUrl(orderId)
        );
    }
    
    /**
     * 카드 타입 문자열을 CardType enum으로 변환합니다.
     *
     * @param cardType 카드 타입 문자열
     * @return CardType enum
     * @throws CoreException 잘못된 카드 타입인 경우
     */
    private PaymentGatewayDto.CardType parseCardType(String cardType) {
        try {
            return PaymentGatewayDto.CardType.valueOf(cardType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                String.format("잘못된 카드 타입입니다. (cardType: %s)", cardType));
        }
    }
    
    /**
     * 콜백 URL을 생성합니다.
     *
     * @param orderId 주문 ID
     * @return 콜백 URL
     */
    private String generateCallbackUrl(Long orderId) {
        return String.format("http://localhost:%d/api/v1/orders/%d/callback", serverPort, orderId);
    }
}

