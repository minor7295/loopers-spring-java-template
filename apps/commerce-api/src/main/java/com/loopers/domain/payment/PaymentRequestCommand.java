package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * 결제 요청 명령.
 * <p>
 * PG 결제 요청에 필요한 정보를 담는 도메인 모델입니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public record PaymentRequestCommand(
    String userId,
    Long orderId,
    String cardType,
    String cardNo,
    Long amount,
    String callbackUrl
) {
    public PaymentRequestCommand {
        if (userId == null || userId.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "userId는 필수입니다.");
        }
        if (orderId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "orderId는 필수입니다.");
        }
        if (cardType == null || cardType.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "cardType은 필수입니다.");
        }
        if (cardNo == null || cardNo.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "cardNo는 필수입니다.");
        }
        if (amount == null || amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "amount는 0보다 커야 합니다.");
        }
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "callbackUrl은 필수입니다.");
        }
    }
}

