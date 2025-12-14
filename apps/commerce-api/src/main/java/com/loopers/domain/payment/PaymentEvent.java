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

        /**
         * 마스킹된 카드 번호를 반환합니다.
         * <p>
         * PII 보호를 위해 카드 번호의 중간 부분을 마스킹합니다.
         * 마지막 4자리만 표시하고 나머지는 *로 마스킹합니다.
         * 예: "4111-1234-5678-9010" -> "****-****-****-9010"
         *     "4111123456789010" -> "************9010"
         * </p>
         *
         * @return 마스킹된 카드 번호 (cardNo가 null이거나 비어있으면 null 반환)
         */
        public String maskedCardNo() {
            if (cardNo == null || cardNo.isBlank()) {
                return null;
            }

            // 숫자만 추출
            String digitsOnly = cardNo.replaceAll("[^0-9]", "");
            
            if (digitsOnly.length() < 4) {
                // 카드 번호가 너무 짧으면 전체 마스킹
                return "****";
            }

            // 마지막 4자리 추출
            String lastFour = digitsOnly.substring(digitsOnly.length() - 4);

            // 원본에 하이픈이 있었다면 하이픈 패턴 유지
            if (cardNo.contains("-")) {
                // 하이픈으로 구분된 각 부분 처리
                String[] parts = cardNo.split("-");
                StringBuilder result = new StringBuilder();
                
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        result.append("-");
                    }
                    
                    String part = parts[i].replaceAll("[^0-9]", "");
                    if (i == parts.length - 1 && part.length() >= 4) {
                        // 마지막 부분은 마지막 4자리만 표시
                        result.append("*".repeat(part.length() - 4)).append(lastFour);
                    } else {
                        // 중간 부분은 모두 마스킹
                        result.append("*".repeat(part.length()));
                    }
                }
                return result.toString();
            } else {
                // 하이픈이 없으면 마스킹된 부분과 마지막 4자리만 반환
                int maskedLength = digitsOnly.length() - 4;
                return "*".repeat(maskedLength) + lastFour;
            }
        }

        /**
         * 로깅 및 이벤트 저장 시 사용할 수 있도록 마스킹된 정보를 포함한 문자열을 반환합니다.
         * <p>
         * PII 보호를 위해 cardNo는 마스킹된 버전으로 출력됩니다.
         * </p>
         *
         * @return 마스킹된 정보를 포함한 문자열 표현
         */
        @Override
        public String toString() {
            return String.format(
                    "PaymentRequested[orderId=%d, userId='%s', userEntityId=%d, totalAmount=%d, usedPointAmount=%d, cardType='%s', cardNo='%s', occurredAt=%s]",
                    orderId,
                    userId,
                    userEntityId,
                    totalAmount,
                    usedPointAmount,
                    cardType,
                    maskedCardNo(),
                    occurredAt
            );
        }
    }
}
