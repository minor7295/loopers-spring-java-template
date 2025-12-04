package com.loopers.domain.order;

/**
 * 결제 실패 유형.
 * <p>
 * 결제 실패를 비즈니스 실패와 외부 시스템 장애로 구분합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public enum PaymentFailureType {
    /**
     * 비즈니스 실패: 주문 취소 필요
     * 예: 카드 한도 초과, 잘못된 카드 번호 등
     */
    BUSINESS_FAILURE,
    
    /**
     * 외부 시스템 장애: 주문 PENDING 상태 유지
     * 예: 서버 오류, 타임아웃 등
     */
    EXTERNAL_SYSTEM_FAILURE
}

