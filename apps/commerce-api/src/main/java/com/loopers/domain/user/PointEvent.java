package com.loopers.domain.user;

import java.time.LocalDateTime;

/**
 * 포인트 도메인 이벤트.
 * <p>
 * 포인트 도메인의 중요한 상태 변화를 나타내는 이벤트들입니다.
 * </p>
 */
public class PointEvent {

    /**
     * 포인트 사용 이벤트.
     * <p>
     * 주문에서 포인트를 사용할 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID (Long - User.id)
     * @param usedPointAmount 사용할 포인트 금액
     * @param occurredAt 이벤트 발생 시각
     */
    public record PointUsed(
            Long orderId,
            Long userId,
            Long usedPointAmount,
            LocalDateTime occurredAt
    ) {
        public PointUsed {
            if (orderId == null) {
                throw new IllegalArgumentException("orderId는 필수입니다.");
            }
            if (userId == null) {
                throw new IllegalArgumentException("userId는 필수입니다.");
            }
            if (usedPointAmount == null || usedPointAmount < 0) {
                throw new IllegalArgumentException("usedPointAmount는 0 이상이어야 합니다.");
            }
        }

        /**
         * OrderCreated 이벤트로부터 PointUsed 이벤트를 생성합니다.
         *
         * @param orderId 주문 ID
         * @param userId 사용자 ID
         * @param usedPointAmount 사용할 포인트 금액
         * @return PointUsed 이벤트
         */
        public static PointUsed of(Long orderId, Long userId, Long usedPointAmount) {
            return new PointUsed(
                    orderId,
                    userId,
                    usedPointAmount,
                    LocalDateTime.now()
            );
        }
    }
}

