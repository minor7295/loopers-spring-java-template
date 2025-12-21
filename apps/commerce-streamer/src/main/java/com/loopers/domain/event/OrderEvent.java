package com.loopers.domain.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 이벤트 DTO.
 * <p>
 * Kafka에서 수신한 주문 이벤트를 파싱하기 위한 DTO입니다.
 * <b>주의:</b> 이 클래스는 commerce-api의 OrderEvent와 동일한 구조를 가진 DTO입니다.
 * 향후 공유 모듈로 분리하는 것을 고려해야 합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public class OrderEvent {

    /**
     * 주문 생성 이벤트.
     */
    public record OrderCreated(
            Long orderId,
            Long userId,
            String couponCode,
            Integer subtotal,
            Long usedPointAmount,
            List<OrderItemInfo> orderItems,
            LocalDateTime createdAt
    ) {
        /**
         * 주문 아이템 정보.
         */
        public record OrderItemInfo(
                Long productId,
                Integer quantity
        ) {
        }
    }
}
