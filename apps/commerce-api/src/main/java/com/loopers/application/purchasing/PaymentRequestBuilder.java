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
        // 주문 ID를 6자리 이상 문자열로 변환 (pg-simulator 검증 요구사항)
        String orderIdString = formatOrderId(orderId);
        return new PaymentRequest(
            userId,
            orderIdString,
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
    
    /**
     * 주문 ID를 6자리 이상 문자열로 변환합니다.
     * <p>
     * pg-simulator의 검증 요구사항에 맞추기 위해 최소 6자리로 패딩합니다.
     * </p>
     *
     * @param orderId 주문 ID (Long)
     * @return 6자리 이상의 주문 ID 문자열
     */
    public String formatOrderId(Long orderId) {
        return String.format("%06d", orderId);
    }
}

