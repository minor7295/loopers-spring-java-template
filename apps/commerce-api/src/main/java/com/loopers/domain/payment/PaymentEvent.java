package com.loopers.domain.payment;

import java.time.LocalDateTime;

/**
 * 결제 도메인 이벤트.
 * <p>
 * 결제 도메인의 중요한 상태 변화를 나타내는 이벤트들입니다.
 * </p>
 */
public class PaymentEvent {

    /**
     * 결제 완료 이벤트.
     * <p>
     * 결제가 성공적으로 완료되었을 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param paymentId 결제 ID
     * @param transactionKey 트랜잭션 키 (null 가능 - PG 응답 전에는 없을 수 있음)
     * @param completedAt 결제 완료 시각
     */
    public record PaymentCompleted(
            Long orderId,
            Long paymentId,
            String transactionKey,
            LocalDateTime completedAt
    ) {
        public PaymentCompleted {
            if (orderId == null) {
                throw new IllegalArgumentException("orderId는 필수입니다.");
            }
            if (paymentId == null) {
                throw new IllegalArgumentException("paymentId는 필수입니다.");
            }
        }

        /**
         * Payment 엔티티와 transactionKey로부터 PaymentCompleted 이벤트를 생성합니다.
         *
         * @param payment 결제 엔티티
         * @param transactionKey 트랜잭션 키 (null 가능)
         * @return PaymentCompleted 이벤트
         */
        public static PaymentCompleted from(Payment payment, String transactionKey) {
            return new PaymentCompleted(
                    payment.getOrderId(),
                    payment.getId(),
                    transactionKey,
                    payment.getPgCompletedAt() != null ? payment.getPgCompletedAt() : LocalDateTime.now()
            );
        }
    }

    /**
     * 결제 실패 이벤트.
     * <p>
     * 결제가 실패했을 때 발행되는 이벤트입니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param paymentId 결제 ID
     * @param transactionKey 트랜잭션 키 (null 가능)
     * @param reason 실패 사유
     * @param refundPointAmount 환불할 포인트 금액
     * @param failedAt 결제 실패 시각
     */
    public record PaymentFailed(
            Long orderId,
            Long paymentId,
            String transactionKey,
            String reason,
            Long refundPointAmount,
            LocalDateTime failedAt
    ) {
        public PaymentFailed {
            if (orderId == null) {
                throw new IllegalArgumentException("orderId는 필수입니다.");
            }
            if (paymentId == null) {
                throw new IllegalArgumentException("paymentId는 필수입니다.");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason은 필수입니다.");
            }
            if (refundPointAmount == null || refundPointAmount < 0) {
                throw new IllegalArgumentException("refundPointAmount는 0 이상이어야 합니다.");
            }
        }

        /**
         * Payment 엔티티와 transactionKey로부터 PaymentFailed 이벤트를 생성합니다.
         *
         * @param payment 결제 엔티티
         * @param reason 실패 사유
         * @param transactionKey 트랜잭션 키 (null 가능)
         * @return PaymentFailed 이벤트
         */
        public static PaymentFailed from(Payment payment, String reason, String transactionKey) {
            return new PaymentFailed(
                    payment.getOrderId(),
                    payment.getId(),
                    transactionKey,
                    reason,
                    payment.getUsedPoint(),
                    payment.getPgCompletedAt() != null ? payment.getPgCompletedAt() : LocalDateTime.now()
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
     * @param occurredAt 이벤트 발생 시각
     */
    public record PaymentRequested(
            Long orderId,
            String userId,
            Long userEntityId,
            Long totalAmount,
            Long usedPointAmount,
            String cardType,
            String cardNo,
            LocalDateTime occurredAt
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

        /**
         * 결제 요청 이벤트를 생성합니다.
         *
         * @param orderId 주문 ID
         * @param userId 사용자 ID (String - User.userId)
         * @param userEntityId 사용자 엔티티 ID (Long - User.id)
         * @param totalAmount 주문 총액
         * @param usedPointAmount 사용된 포인트 금액
         * @param cardType 카드 타입 (null 가능)
         * @param cardNo 카드 번호 (null 가능)
         * @return PaymentRequested 이벤트
         */
        public static PaymentRequested of(
                Long orderId,
                String userId,
                Long userEntityId,
                Long totalAmount,
                Long usedPointAmount,
                String cardType,
                String cardNo
        ) {
            return new PaymentRequested(
                    orderId,
                    userId,
                    userEntityId,
                    totalAmount,
                    usedPointAmount,
                    cardType,
                    cardNo,
                    LocalDateTime.now()
            );
        }
    }
}
