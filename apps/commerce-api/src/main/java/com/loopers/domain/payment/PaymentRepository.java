package com.loopers.domain.payment;

import java.util.List;
import java.util.Optional;

/**
 * 결제 저장소 인터페이스.
 * <p>
 * Payment 엔티티의 영속성 계층 접근을 추상화합니다.
 * </p>
 */
public interface PaymentRepository {

    /**
     * 결제를 저장합니다.
     *
     * @param payment 저장할 결제
     * @return 저장된 결제
     */
    Payment save(Payment payment);

    /**
     * 결제 ID로 결제를 조회합니다.
     *
     * @param paymentId 조회할 결제 ID
     * @return 조회된 결제
     */
    Optional<Payment> findById(Long paymentId);

    /**
     * 주문 ID로 결제를 조회합니다.
     *
     * @param orderId 주문 ID
     * @return 조회된 결제
     */
    Optional<Payment> findByOrderId(Long orderId);

    /**
     * 사용자 ID로 결제 목록을 조회합니다.
     *
     * @param userId 사용자 ID
     * @return 해당 사용자의 결제 목록
     */
    List<Payment> findAllByUserId(Long userId);

    /**
     * 결제 상태로 결제 목록을 조회합니다.
     *
     * @param status 결제 상태
     * @return 해당 상태의 결제 목록
     */
    List<Payment> findAllByStatus(PaymentStatus status);
}

