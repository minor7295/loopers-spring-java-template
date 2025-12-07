package com.loopers.domain.payment;

import java.time.LocalDateTime;

/**
 * 테스트용 고정 데이터 (Fixture) 클래스
 * 모든 Payment 관련 테스트에서 사용하는 공통 데이터를 관리
 */
public class PaymentTestFixture {

    // 기본 유효한 테스트 데이터
    public static final class ValidPayment {
        public static final Long ORDER_ID = 1L;
        public static final Long USER_ID = 100L;
        public static final Long AMOUNT = 50000L;
        public static final CardType CARD_TYPE = CardType.SAMSUNG;
        public static final String CARD_NO = "4111-1111-1111-1111";
        public static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2025, 12, 1, 10, 0, 0);
        public static final String TRANSACTION_KEY = "tx-key-12345";
        public static final Long ZERO_POINT = 0L;
        public static final Long FULL_POINT = AMOUNT; // 전액 포인트
        public static final Long PARTIAL_POINT = AMOUNT / 2; // 부분 포인트
    }

    // 유효하지 않은 테스트 데이터
    public static final class InvalidPayment {
        public static final Long INVALID_AMOUNT = 0L;
    }
}

