package com.loopers.domain.order;

import java.util.function.Function;

/**
 * 결제 결과 도메인 모델.
 * <p>
 * 결제 요청의 성공/실패 결과를 표현합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public sealed interface PaymentResult {
    
    /**
     * 성공 결과.
     */
    record Success(String transactionKey) implements PaymentResult {
    }
    
    /**
     * 실패 결과.
     */
    record Failure(
        String errorCode,
        String message,
        boolean isTimeout,
        boolean isServerError,
        boolean isClientError
    ) implements PaymentResult {
    }
    
    /**
     * 결과에 따라 처리합니다.
     *
     * @param successHandler 성공 시 처리 함수
     * @param failureHandler 실패 시 처리 함수
     * @param <T> 반환 타입
     * @return 처리 결과
     */
    default <T> T handle(
        Function<Success, T> successHandler,
        Function<Failure, T> failureHandler
    ) {
        successHandler.apply(success);
//        return switch (this) {
//            case Success success -> successHandler.apply(success);
//            case Failure failure -> failureHandler.apply(failure);
//        };
    }
}

