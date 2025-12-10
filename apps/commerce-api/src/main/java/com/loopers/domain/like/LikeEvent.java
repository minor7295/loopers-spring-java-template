package com.loopers.domain.like;

import java.time.LocalDateTime;

/**
 * 좋아요 도메인 이벤트.
 * <p>
 * 좋아요 도메인의 중요한 상태 변화를 나타내는 이벤트들입니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public class LikeEvent {

    /**
     * 좋아요 추가 이벤트.
     * <p>
     * 좋아요가 추가되었을 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param userId 사용자 ID (Long - User.id)
     * @param productId 상품 ID
     * @param occurredAt 이벤트 발생 시각
     */
    public record LikeAdded(
            Long userId,
            Long productId,
            LocalDateTime occurredAt
    ) {
        public LikeAdded {
            if (userId == null) {
                throw new IllegalArgumentException("userId는 필수입니다.");
            }
            if (productId == null) {
                throw new IllegalArgumentException("productId는 필수입니다.");
            }
        }

        /**
         * Like 엔티티로부터 LikeAdded 이벤트를 생성합니다.
         *
         * @param like 좋아요 엔티티
         * @return LikeAdded 이벤트
         */
        public static LikeAdded from(Like like) {
            return new LikeAdded(
                    like.getUserId(),
                    like.getProductId(),
                    LocalDateTime.now()
            );
        }
    }

    /**
     * 좋아요 취소 이벤트.
     * <p>
     * 좋아요가 취소되었을 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param userId 사용자 ID (Long - User.id)
     * @param productId 상품 ID
     * @param occurredAt 이벤트 발생 시각
     */
    public record LikeRemoved(
            Long userId,
            Long productId,
            LocalDateTime occurredAt
    ) {
        public LikeRemoved {
            if (userId == null) {
                throw new IllegalArgumentException("userId는 필수입니다.");
            }
            if (productId == null) {
                throw new IllegalArgumentException("productId는 필수입니다.");
            }
        }

        /**
         * Like 엔티티로부터 LikeRemoved 이벤트를 생성합니다.
         *
         * @param like 좋아요 엔티티
         * @return LikeRemoved 이벤트
         */
        public static LikeRemoved from(Like like) {
            return new LikeRemoved(
                    like.getUserId(),
                    like.getProductId(),
                    LocalDateTime.now()
            );
        }
    }
}

