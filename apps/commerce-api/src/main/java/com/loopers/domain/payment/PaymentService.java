package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 결제 도메인 서비스.
 * <p>
 * 결제의 생성, 조회, 상태 변경을 담당합니다.
 * 도메인 로직은 Payment 엔티티에 위임하며, Service는 조회/저장만 담당합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    /**
     * 카드 결제를 생성합니다.
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param cardType 카드 타입
     * @param cardNo 카드 번호
     * @param amount 결제 금액
     * @param requestedAt PG 요청 시각
     * @return 생성된 Payment
     */
    @Transactional
    public Payment create(
        Long orderId,
        Long userId,
        CardType cardType,
        String cardNo,
        Long amount,
        LocalDateTime requestedAt
    ) {
        Payment payment = Payment.of(orderId, userId, cardType, cardNo, amount, requestedAt);
        return paymentRepository.save(payment);
    }

    /**
     * 포인트 결제를 생성합니다.
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param totalAmount 총 결제 금액
     * @param usedPoint 사용 포인트
     * @param requestedAt PG 요청 시각
     * @return 생성된 Payment
     */
    @Transactional
    public Payment create(
        Long orderId,
        Long userId,
        Long totalAmount,
        Long usedPoint,
        LocalDateTime requestedAt
    ) {
        Payment payment = Payment.of(orderId, userId, totalAmount, usedPoint, requestedAt);
        return paymentRepository.save(payment);
    }

    /**
     * 결제를 SUCCESS 상태로 전이합니다.
     * <p>
     * 멱등성 보장: 이미 SUCCESS 상태인 경우 아무 작업도 하지 않습니다.
     * </p>
     *
     * @param paymentId 결제 ID
     * @param completedAt PG 완료 시각
     * @throws CoreException 결제를 찾을 수 없는 경우
     */
    @Transactional
    public void toSuccess(Long paymentId, LocalDateTime completedAt) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제를 찾을 수 없습니다."));
        payment.toSuccess(completedAt); // Entity에 위임
        paymentRepository.save(payment);
    }

    /**
     * 결제를 FAILED 상태로 전이합니다.
     * <p>
     * 멱등성 보장: 이미 FAILED 상태인 경우 아무 작업도 하지 않습니다.
     * </p>
     *
     * @param paymentId 결제 ID
     * @param failureReason 실패 사유
     * @param completedAt PG 완료 시각
     * @throws CoreException 결제를 찾을 수 없는 경우
     */
    @Transactional
    public void toFailed(Long paymentId, String failureReason, LocalDateTime completedAt) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제를 찾을 수 없습니다."));
        payment.toFailed(failureReason, completedAt); // Entity에 위임
        paymentRepository.save(payment);
    }

    /**
     * 결제 ID로 결제를 조회합니다.
     *
     * @param paymentId 결제 ID
     * @return 조회된 Payment
     * @throws CoreException 결제를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public Payment findById(Long paymentId) {
        return paymentRepository.findById(paymentId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제를 찾을 수 없습니다."));
    }

    /**
     * 주문 ID로 결제를 조회합니다.
     *
     * @param orderId 주문 ID
     * @return 조회된 Payment (없으면 Optional.empty())
     */
    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    /**
     * 사용자 ID로 결제 목록을 조회합니다.
     *
     * @param userId 사용자 ID
     * @return 해당 사용자의 결제 목록
     */
    @Transactional(readOnly = true)
    public List<Payment> findAllByUserId(Long userId) {
        return paymentRepository.findAllByUserId(userId);
    }

    /**
     * 결제 상태로 결제 목록을 조회합니다.
     *
     * @param status 결제 상태
     * @return 해당 상태의 결제 목록
     */
    @Transactional(readOnly = true)
    public List<Payment> findAllByStatus(PaymentStatus status) {
        return paymentRepository.findAllByStatus(status);
    }
}

