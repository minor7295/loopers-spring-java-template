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
    
    @Value("${payment.callback.base-url}")
    private String callbackBaseUrl;
    
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
        // 카드 번호 유효성 검증
        validateCardNo(cardNo);
        
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
     * <p>
     * 환경변수 {@code payment.callback.base-url}을 사용하여 프로덕션 환경에 적합한 URL을 생성합니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @return 콜백 URL
     */
    private String generateCallbackUrl(Long orderId) {
        return String.format("%s/api/v1/orders/%d/callback", callbackBaseUrl, orderId);
    }
    
    /**
     * 카드 번호 유효성 검증을 수행합니다.
     * <p>
     * 다음 사항들을 검증합니다:
     * <ul>
     *   <li>null/empty 체크</li>
     *   <li>공백/하이픈 제거 및 정규화</li>
     *   <li>길이 검증 (13-19자리)</li>
     *   <li>숫자만 포함하는지 검증</li>
     *   <li>Luhn 알고리즘 체크섬 검증</li>
     * </ul>
     * </p>
     *
     * @param cardNo 카드 번호
     * @throws CoreException 유효하지 않은 카드 번호인 경우
     */
    private void validateCardNo(String cardNo) {
        if (cardNo == null || cardNo.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 번호는 필수입니다.");
        }
        
        // 공백/하이픈 제거 및 정규화
        String normalized = cardNo.replaceAll("[\\s-]", "");
        
        // 길이 검증 (13-19자리)
        if (normalized.length() < 13 || normalized.length() > 19) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                String.format("유효하지 않은 카드 번호 길이입니다. (길이: %d, 요구사항: 13-19자리)", normalized.length()));
        }
        
        // 숫자만 포함하는지 검증
        if (!normalized.matches("\\d+")) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 번호는 숫자만 포함해야 합니다.");
        }
        
        // Luhn 알고리즘 체크섬 검증
        if (!isValidLuhn(normalized)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "유효하지 않은 카드 번호입니다. (Luhn 알고리즘 검증 실패)");
        }
    }
    
    /**
     * Luhn 알고리즘을 사용하여 카드 번호의 체크섬을 검증합니다.
     * <p>
     * Luhn 알고리즘은 신용카드 번호의 유효성을 검증하는 표준 알고리즘입니다.
     * </p>
     *
     * @param cardNo 정규화된 카드 번호 (숫자만 포함)
     * @return 유효한 경우 true, 그렇지 않으면 false
     */
    private boolean isValidLuhn(String cardNo) {
        int sum = 0;
        boolean alternate = false;
        
        // 오른쪽에서 왼쪽으로 순회
        for (int i = cardNo.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(cardNo.charAt(i));
            
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit = (digit % 10) + 1;
                }
            }
            
            sum += digit;
            alternate = !alternate;
        }
        
        return (sum % 10) == 0;
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

