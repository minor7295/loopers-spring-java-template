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
     * @param failedAt 결제 실패 시각
     */
    public record PaymentFailed(
        Long orderId,
        Long paymentId,
        String transactionKey,
        String reason,
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
                payment.getPgCompletedAt() != null ? payment.getPgCompletedAt() : LocalDateTime.now()
            );
        }
    }
    }
}
