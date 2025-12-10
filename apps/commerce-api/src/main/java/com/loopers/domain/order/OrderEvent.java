package com.loopers.domain.order;

import java.time.LocalDateTime;
import java.util.List;

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
     * @param usedPointAmount 사용할 포인트 금액
     * @param createdAt 이벤트 발생 시각
     */
    public record OrderCreated(
            Long orderId,
            Long userId,
            String couponCode,
            Integer subtotal,
            Long usedPointAmount,
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
            if (usedPointAmount == null || usedPointAmount < 0) {
                throw new IllegalArgumentException("usedPointAmount는 0 이상이어야 합니다.");
            }
        }

        /**
         * Order 엔티티로부터 OrderCreated 이벤트를 생성합니다.
         *
         * @param order 주문 엔티티
         * @param subtotal 주문 소계 (쿠폰 할인 전 금액)
         * @param usedPointAmount 사용할 포인트 금액
         * @return OrderCreated 이벤트
         */
        public static OrderCreated from(Order order, Integer subtotal, Long usedPointAmount) {
            return new OrderCreated(
                    order.getId(),
                    order.getUserId(),
                    order.getCouponCode(),
                    subtotal,
                    usedPointAmount,
                    LocalDateTime.now()
            );
        }
    }

    /**
     * 포인트 사용 이벤트.
     * <p>
     * 주문에서 포인트를 사용할 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID (Long - User.id)
     * @param usedPointAmount 사용할 포인트 금액
     * @param createdAt 이벤트 발생 시각
     */
    public record PointUsed(
            Long orderId,
            Long userId,
            Long usedPointAmount,
            LocalDateTime createdAt
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
         * @param event OrderCreated 이벤트
         * @return PointUsed 이벤트
         */
        public static PointUsed from(OrderCreated event) {
            return new PointUsed(
                    event.orderId(),
                    event.userId(),
                    event.usedPointAmount(),
                    LocalDateTime.now()
            );
        }
    }

    /**
     * 결제 요청 이벤트.
     * <p>
     * 주문에 대한 결제를 요청할 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID (String - User.userId, PG 요청용)
     * @param userEntityId 사용자 엔티티 ID (Long - User.id, Payment 엔티티용)
     * @param totalAmount 주문 총액
     * @param usedPointAmount 사용된 포인트 금액
     * @param cardType 카드 타입 (null 가능)
     * @param cardNo 카드 번호 (null 가능)
     * @param createdAt 이벤트 발생 시각
     */
    public record PaymentRequested(
            Long orderId,
            String userId,
            Long userEntityId,
            Long totalAmount,
            Long usedPointAmount,
            String cardType,
            String cardNo,
            LocalDateTime createdAt
    ) {
        public PaymentRequested {
            if (orderId == null) {
                throw new IllegalArgumentException("orderId는 필수입니다.");
            }
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId는 필수입니다.");
            }
            if (userEntityId == null) {
                throw new IllegalArgumentException("userEntityId는 필수입니다.");
            }
            if (totalAmount == null || totalAmount < 0) {
                throw new IllegalArgumentException("totalAmount는 0 이상이어야 합니다.");
            }
            if (usedPointAmount == null || usedPointAmount < 0) {
                throw new IllegalArgumentException("usedPointAmount는 0 이상이어야 합니다.");
            }
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
     * @param orderItems 주문 아이템 목록 (재고 원복용)
     * @param refundPointAmount 환불할 포인트 금액
     * @param canceledAt 주문 취소 시각
     */
    public record OrderCanceled(
            Long orderId,
            Long userId,
            String reason,
            List<OrderItemInfo> orderItems,
            Long refundPointAmount,
            LocalDateTime canceledAt
    ) {
        /**
         * 주문 아이템 정보 (재고 원복용).
         *
         * @param productId 상품 ID
         * @param quantity 수량
         */
        public record OrderItemInfo(
                Long productId,
                Integer quantity
        ) {
            public OrderItemInfo {
                if (productId == null) {
                    throw new IllegalArgumentException("productId는 필수입니다.");
                }
                if (quantity == null || quantity <= 0) {
                    throw new IllegalArgumentException("quantity는 1 이상이어야 합니다.");
                }
            }
        }

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
            if (orderItems == null) {
                throw new IllegalArgumentException("orderItems는 필수입니다.");
            }
            if (refundPointAmount == null || refundPointAmount < 0) {
                throw new IllegalArgumentException("refundPointAmount는 0 이상이어야 합니다.");
            }
        }

        /**
         * Order 엔티티와 환불 포인트 금액으로부터 OrderCanceled 이벤트를 생성합니다.
         *
         * @param order 주문 엔티티
         * @param reason 취소 사유
         * @param refundPointAmount 환불할 포인트 금액
         * @return OrderCanceled 이벤트
         */
        public static OrderCanceled from(Order order, String reason, Long refundPointAmount) {
            List<OrderItemInfo> orderItemInfos = order.getItems().stream()
                    .map(item -> new OrderItemInfo(item.getProductId(), item.getQuantity()))
                    .toList();

            return new OrderCanceled(
                    order.getId(),
                    order.getUserId(),
                    reason,
                    orderItemInfos,
                    refundPointAmount,
                    LocalDateTime.now()
            );
        }
    }
}
