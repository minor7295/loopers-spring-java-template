package com.loopers.domain.event;

import java.time.LocalDateTime;

/**
 * 좋아요 이벤트 DTO.
 * <p>
 * Kafka에서 수신한 좋아요 이벤트를 파싱하기 위한 DTO입니다.
 * <b>주의:</b> 이 클래스는 commerce-api의 LikeEvent와 동일한 구조를 가진 DTO입니다.
 * 향후 공유 모듈로 분리하는 것을 고려해야 합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public class LikeEvent {

    /**
     * 좋아요 추가 이벤트.
     */
    public record LikeAdded(
            Long userId,
            Long productId,
            LocalDateTime occurredAt
    ) {
    }

    /**
     * 좋아요 취소 이벤트.
     */
    public record LikeRemoved(
            Long userId,
            Long productId,
            LocalDateTime occurredAt
    ) {
    }
}
