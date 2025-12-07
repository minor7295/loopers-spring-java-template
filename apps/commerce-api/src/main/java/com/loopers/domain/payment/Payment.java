package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 결제 도메인 엔티티.
 * <p>
 * 결제의 상태, 금액, 포인트 사용 정보를 관리합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Entity
@Table(
    name = "payment",
    indexes = {
        @Index(name = "idx_payment_order_id", columnList = "ref_order_id"),
        @Index(name = "idx_payment_user_id", columnList = "ref_user_id"),
        @Index(name = "idx_payment_status", columnList = "status")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Payment extends BaseEntity {

    @Column(name = "ref_order_id", nullable = false)
    private Long orderId;

    @Column(name = "ref_user_id", nullable = false)
    private Long userId;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "used_point", nullable = false)
    private Long usedPoint;

    @Column(name = "paid_amount", nullable = false)
    private Long paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_type")
    private CardType cardType;

    @Column(name = "card_no")
    private String cardNo;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "pg_requested_at", nullable = false)
    private LocalDateTime pgRequestedAt;

    @Column(name = "pg_completed_at")
    private LocalDateTime pgCompletedAt;

    /**
     * 카드 결제용 Payment를 생성합니다.
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param cardType 카드 타입
     * @param cardNo 카드 번호
     * @param amount 결제 금액
     * @param requestedAt PG 요청 시각
     * @return 생성된 Payment 인스턴스
     * @throws CoreException 유효성 검증 실패 시
     */
    public static Payment of(
        Long orderId,
        Long userId,
        CardType cardType,
        String cardNo,
        Long amount,
        LocalDateTime requestedAt
    ) {
        validateOrderId(orderId);
        validateUserId(userId);
        validateCardType(cardType);
        validateCardNo(cardNo);
        validateAmount(amount);
        validateRequestedAt(requestedAt);

        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.userId = userId;
        payment.totalAmount = amount;
        payment.usedPoint = 0L;
        payment.paidAmount = amount;
        payment.status = PaymentStatus.PENDING;
        payment.cardType = cardType;
        payment.cardNo = cardNo;
        payment.pgRequestedAt = requestedAt;

        return payment;
    }

    /**
     * 포인트 결제용 Payment를 생성합니다.
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param totalAmount 총 결제 금액
     * @param usedPoint 사용 포인트
     * @param requestedAt PG 요청 시각
     * @return 생성된 Payment 인스턴스
     * @throws CoreException 유효성 검증 실패 시
     */
    public static Payment of(
        Long orderId,
        Long userId,
        Long totalAmount,
        Long usedPoint,
        LocalDateTime requestedAt
    ) {
        validateOrderId(orderId);
        validateUserId(userId);
        validateAmount(totalAmount);
        validateUsedPoint(usedPoint);
        validateRequestedAt(requestedAt);

        Long paidAmount = totalAmount - usedPoint;
        validatePaidAmount(paidAmount);

        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.userId = userId;
        payment.totalAmount = totalAmount;
        payment.usedPoint = usedPoint;
        payment.paidAmount = paidAmount;
        payment.status = (paidAmount == 0L) ? PaymentStatus.SUCCESS : PaymentStatus.PENDING;
        payment.cardType = null;
        payment.cardNo = null;
        payment.pgRequestedAt = requestedAt;

        return payment;
    }

    /**
     * 결제를 SUCCESS 상태로 전이합니다.
     * <p>
     * 멱등성 보장: 이미 SUCCESS 상태인 경우 아무 작업도 하지 않습니다.
     * </p>
     *
     * @param completedAt PG 완료 시각
     * @throws CoreException PENDING 상태가 아닌 경우 (SUCCESS는 제외)
     */
    public void toSuccess(LocalDateTime completedAt) {
        if (status == PaymentStatus.SUCCESS) {
            // 멱등성: 이미 성공 상태면 아무 작업도 하지 않음
            return;
        }
        if (status != PaymentStatus.PENDING) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 대기 상태에서만 성공으로 전이할 수 있습니다.");
        }
        this.status = PaymentStatus.SUCCESS;
        this.pgCompletedAt = completedAt;
    }

    /**
     * 결제를 FAILED 상태로 전이합니다.
     * <p>
     * 멱등성 보장: 이미 FAILED 상태인 경우 아무 작업도 하지 않습니다.
     * </p>
     *
     * @param failureReason 실패 사유
     * @param completedAt PG 완료 시각
     * @throws CoreException PENDING 상태가 아닌 경우 (FAILED는 제외)
     */
    public void toFailed(String failureReason, LocalDateTime completedAt) {
        if (status == PaymentStatus.FAILED) {
            // 멱등성: 이미 실패 상태면 아무 작업도 하지 않음
            return;
        }
        if (status != PaymentStatus.PENDING) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 대기 상태에서만 실패로 전이할 수 있습니다.");
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = failureReason;
        this.pgCompletedAt = completedAt;
    }

    /**
     * 결제가 완료되었는지 확인합니다.
     *
     * @return 완료 여부
     */
    public boolean isCompleted() {
        return status.isCompleted();
    }

    private static void validateOrderId(Long orderId) {
        if (orderId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 ID는 필수입니다.");
        }
    }

    private static void validateUserId(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자 ID는 필수입니다.");
        }
    }

    private static void validateCardType(CardType cardType) {
        if (cardType == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 타입은 필수입니다.");
        }
    }

    private static void validateCardNo(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 번호는 필수입니다.");
        }
    }

    private static void validateAmount(Long amount) {
        if (amount == null || amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 0보다 커야 합니다.");
        }
    }

    private static void validateUsedPoint(Long usedPoint) {
        if (usedPoint == null || usedPoint < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용 포인트는 0 이상이어야 합니다.");
        }
    }

    private static void validatePaidAmount(Long paidAmount) {
        if (paidAmount < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "포인트와 쿠폰 할인의 합이 주문 금액을 초과합니다.");
        }
    }

    private static void validateRequestedAt(LocalDateTime requestedAt) {
        if (requestedAt == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "PG 요청 시각은 필수입니다.");
        }
    }
}

