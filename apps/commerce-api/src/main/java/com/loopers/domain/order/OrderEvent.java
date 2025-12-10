package com.loopers.domain.order;

import java.time.LocalDateTime;

/**
 * 주문 도메인 이벤트.
 * <p>
 * 주문 도메인의 중요한 상태 변화를 나타내는 이벤트들입니다.
 * </p>
 */
public class OrderEvent {

    /**
     * 주문 생성 이벤트.
     * <p>
     * 주문이 생성되었을 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID (Long - User.id)
     * @param couponCode 쿠폰 코드 (null 가능)
     * @param subtotal 주문 소계 (쿠폰 할인 전 금액)
     * @param createdAt 이벤트 발생 시각
     */
    public record OrderCreated(
            Long orderId,
            Long userId,
            String couponCode,
            Integer subtotal,
            LocalDateTime createdAt
    ) {
        public OrderCreated {
            if (orderId == null) {
                throw new IllegalArgumentException("orderId는 필수입니다.");
            }
            if (userId == null) {
                throw new IllegalArgumentException("userId는 필수입니다.");
            }
            if (subtotal == null || subtotal < 0) {
                throw new IllegalArgumentException("subtotal은 0 이상이어야 합니다.");
            }
        }

        /**
         * Order 엔티티로부터 OrderCreated 이벤트를 생성합니다.
         *
         * @param order 주문 엔티티
         * @param subtotal 주문 소계 (쿠폰 할인 전 금액)
         * @return OrderCreated 이벤트
         */
        public static OrderCreated from(Order order, Integer subtotal) {
            return new OrderCreated(
                    order.getId(),
                    order.getUserId(),
                    order.getCouponCode(),
                    subtotal,
                    LocalDateTime.now()
            );
        }
    }

    /**
     * 주문 완료 이벤트.
     * <p>
     * 주문이 완료되었을 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID (Long - User.id)
     * @param totalAmount 주문 총액
     * @param completedAt 주문 완료 시각
     */
    public record OrderCompleted(
            Long orderId,
            Long userId,
            Long totalAmount,
            LocalDateTime completedAt
    ) {
        public OrderCompleted {
            if (orderId == null) {
                throw new IllegalArgumentException("orderId는 필수입니다.");
            }
            if (userId == null) {
                throw new IllegalArgumentException("userId는 필수입니다.");
            }
            if (totalAmount == null || totalAmount < 0) {
                throw new IllegalArgumentException("totalAmount는 0 이상이어야 합니다.");
            }
        }

        /**
         * Order 엔티티로부터 OrderCompleted 이벤트를 생성합니다.
         *
         * @param order 주문 엔티티
         * @return OrderCompleted 이벤트
         */
        public static OrderCompleted from(Order order) {
            return new OrderCompleted(
                    order.getId(),
                    order.getUserId(),
                    order.getTotalAmount().longValue(),
                    LocalDateTime.now()
            );
        }
    }

    /**
     * 주문 취소 이벤트.
     * <p>
     * 주문이 취소되었을 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID (Long - User.id)
     * @param reason 취소 사유
     * @param canceledAt 주문 취소 시각
     */
    public record OrderCanceled(
            Long orderId,
            Long userId,
            String reason,
            LocalDateTime canceledAt
    ) {
        public OrderCanceled {
            if (orderId == null) {
                throw new IllegalArgumentException("orderId는 필수입니다.");
            }
            if (userId == null) {
                throw new IllegalArgumentException("userId는 필수입니다.");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason은 필수입니다.");
            }
        }

        /**
         * Order 엔티티로부터 OrderCanceled 이벤트를 생성합니다.
         *
         * @param order 주문 엔티티
         * @param reason 취소 사유
         * @return OrderCanceled 이벤트
         */
        public static OrderCanceled from(Order order, String reason) {
            return new OrderCanceled(
                    order.getId(),
                    order.getUserId(),
                    reason,
                    LocalDateTime.now()
            );
        }
    }
}
