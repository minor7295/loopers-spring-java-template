package com.loopers.domain.event;

import java.time.LocalDateTime;

/**
 * 상품 이벤트 DTO.
 * <p>
 * Kafka에서 수신한 상품 이벤트를 파싱하기 위한 DTO입니다.
 * <b>주의:</b> 이 클래스는 commerce-api의 ProductEvent와 동일한 구조를 가진 DTO입니다.
 * 향후 공유 모듈로 분리하는 것을 고려해야 합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public class ProductEvent {

    /**
     * 상품 상세 페이지 조회 이벤트.
     */
    public record ProductViewed(
            Long productId,
            Long userId,
            LocalDateTime occurredAt
    ) {
    }
}
