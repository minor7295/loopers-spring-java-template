package com.loopers.domain.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 결제 실패 분류 도메인 서비스.
 * <p>
 * 결제 실패를 비즈니스 실패와 외부 시스템 장애로 분류합니다.
 * </p>
 * <p>
 * <b>비즈니스 실패 예시:</b>
 * <ul>
 *   <li>카드 한도 초과 (LIMIT_EXCEEDED)</li>
 *   <li>잘못된 카드 번호 (INVALID_CARD)</li>
 *   <li>카드 오류 (CARD_ERROR)</li>
 *   <li>잔액 부족 (INSUFFICIENT_FUNDS)</li>
 * </ul>
 * </p>
 * <p>
 * <b>외부 시스템 장애 예시:</b>
 * <ul>
 *   <li>CircuitBreaker Open (CIRCUIT_BREAKER_OPEN)</li>
 *   <li>서버 오류 (5xx)</li>
 *   <li>타임아웃</li>
 *   <li>네트워크 오류</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class PaymentFailureClassifier {
    
    private static final Set<String> BUSINESS_FAILURE_CODES = Set.of(
        "LIMIT_EXCEEDED",
        "INVALID_CARD",
        "CARD_ERROR",
        "INSUFFICIENT_FUNDS",
        "PAYMENT_FAILED"
    );
    
    private static final String CIRCUIT_BREAKER_OPEN = "CIRCUIT_BREAKER_OPEN";
    
    /**
     * 오류 코드를 기반으로 결제 실패 유형을 분류합니다.
     *
     * @param errorCode 오류 코드
     * @return 결제 실패 유형
     */
    public PaymentFailureType classify(String errorCode) {
        if (errorCode == null) {
            return PaymentFailureType.EXTERNAL_SYSTEM_FAILURE;
        }
        
        // CircuitBreaker Open 상태는 명시적으로 외부 시스템 장애로 간주
        if (CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
            return PaymentFailureType.EXTERNAL_SYSTEM_FAILURE;
        }
        
        // 명확한 비즈니스 실패 오류 코드만 취소 처리
        boolean isBusinessFailure = BUSINESS_FAILURE_CODES.stream()
            .anyMatch(errorCode::contains);
        
        return isBusinessFailure
            ? PaymentFailureType.BUSINESS_FAILURE
            : PaymentFailureType.EXTERNAL_SYSTEM_FAILURE;
    }
}

