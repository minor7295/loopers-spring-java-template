package com.loopers.application.user;

/**
 * 포인트 차감 명령.
 * <p>
 * 포인트 차감을 위한 명령 객체입니다.
 * </p>
 *
 * @param userId 사용자 ID
 * @param usedPointAmount 사용할 포인트 금액
 */
public record DeductPointCommand(
    Long userId,
    Long usedPointAmount
) {
    public DeductPointCommand {
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
    }
}

