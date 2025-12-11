package com.loopers.application.payment;

import com.loopers.domain.payment.*;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 결제 애플리케이션 서비스.
 * <p>
 * 결제의 생성, 조회, 상태 변경 및 PG 연동을 담당하는 애플리케이션 서비스입니다.
 * 도메인 로직은 Payment 엔티티에 위임하며, Service는 조회/저장, 트랜잭션 관리 및 PG 연동을 담당합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentEventPublisher paymentEventPublisher;
    
    @Value("${payment.callback.base-url}")
    private String callbackBaseUrl;

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
     * 포인트와 카드 혼합 결제를 생성합니다.
     * <p>
     * 포인트와 쿠폰 할인을 적용한 후 남은 금액을 카드로 결제하는 경우 사용합니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID
     * @param totalAmount 총 결제 금액
     * @param usedPoint 사용 포인트
     * @param cardType 카드 타입 (paidAmount > 0일 때만 필수)
     * @param cardNo 카드 번호 (paidAmount > 0일 때만 필수)
     * @param requestedAt PG 요청 시각
     * @return 생성된 Payment
     */
    @Transactional
    public Payment create(
        Long orderId,
        Long userId,
        Long totalAmount,
        Long usedPoint,
        CardType cardType,
        String cardNo,
        LocalDateTime requestedAt
    ) {
        Payment payment = Payment.of(orderId, userId, totalAmount, usedPoint, cardType, cardNo, requestedAt);
        return paymentRepository.save(payment);
    }

    /**
     * 결제를 SUCCESS 상태로 전이합니다.
     * <p>
     * 멱등성 보장: 이미 SUCCESS 상태인 경우 아무 작업도 하지 않습니다.
     * 결제 완료 후 PaymentCompleted 이벤트를 발행합니다.
     * </p>
     *
     * @param paymentId 결제 ID
     * @param completedAt PG 완료 시각
     * @param transactionKey 트랜잭션 키 (null 가능)
     * @throws CoreException 결제를 찾을 수 없는 경우
     */
    @Transactional
    public void toSuccess(Long paymentId, LocalDateTime completedAt, String transactionKey) {
        Payment payment = getPayment(paymentId);
        
        // 이미 SUCCESS 상태인 경우 이벤트 발행하지 않음 (멱등성)
        if (payment.isCompleted()) {
            return;
        }
        
        payment.toSuccess(completedAt); // Entity에 위임
        Payment savedPayment = paymentRepository.save(payment);
        
        // ✅ 도메인 이벤트 발행: 결제가 완료되었음 (과거 사실)
        paymentEventPublisher.publish(PaymentEvent.PaymentCompleted.from(savedPayment, transactionKey));
    }

    /**
     * 결제를 FAILED 상태로 전이합니다.
     * <p>
     * 멱등성 보장: 이미 FAILED 상태인 경우 아무 작업도 하지 않습니다.
     * 결제 실패 후 PaymentFailed 이벤트를 발행합니다.
     * </p>
     *
     * @param paymentId 결제 ID
     * @param failureReason 실패 사유
     * @param completedAt PG 완료 시각
     * @param transactionKey 트랜잭션 키 (null 가능)
     * @throws CoreException 결제를 찾을 수 없는 경우
     */
    @Transactional
    public void toFailed(Long paymentId, String failureReason, LocalDateTime completedAt, String transactionKey) {
        Payment payment = getPayment(paymentId);
        
        // 이미 FAILED 상태인 경우 이벤트 발행하지 않음 (멱등성)
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }
        
        payment.toFailed(failureReason, completedAt); // Entity에 위임
        Payment savedPayment = paymentRepository.save(payment);
        
        // ✅ 도메인 이벤트 발행: 결제가 실패했음 (과거 사실)
        paymentEventPublisher.publish(PaymentEvent.PaymentFailed.from(savedPayment, failureReason, transactionKey));
    }

    /**
     * 결제 ID로 결제를 조회합니다.
     *
     * @param paymentId 결제 ID
     * @return 조회된 Payment
     * @throws CoreException 결제를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public Payment getPayment(Long paymentId) {
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
    public Optional<Payment> getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    /**
     * 사용자 ID로 결제 목록을 조회합니다.
     *
     * @param userId 사용자 ID
     * @return 해당 사용자의 결제 목록
     */
    @Transactional(readOnly = true)
    public List<Payment> getPaymentsByUserId(Long userId) {
        return paymentRepository.findAllByUserId(userId);
    }

    /**
     * 결제 상태로 결제 목록을 조회합니다.
     *
     * @param status 결제 상태
     * @return 해당 상태의 결제 목록
     */
    @Transactional(readOnly = true)
    public List<Payment> getPaymentsByStatus(PaymentStatus status) {
        return paymentRepository.findAllByStatus(status);
    }
    
    /**
     * PG 결제 요청을 생성하고 전송합니다.
     * <p>
     * 결제를 생성하고 PG에 결제 요청을 전송합니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param userId 사용자 ID (String - User.userId)
     * @param userEntityId 사용자 엔티티 ID (Long - User.id)
     * @param cardType 카드 타입
     * @param cardNo 카드 번호
     * @param amount 결제 금액
     * @return 결제 요청 결과
     */
    @Transactional
    public PaymentRequestResult requestPayment(
        Long orderId,
        String userId,
        Long userEntityId,
        String cardType,
        String cardNo,
        Long amount
    ) {
        // 1. 카드 번호 유효성 검증
        validateCardNo(cardNo);
        
        // 2. 결제 생성 (User 엔티티의 id 사용)
        Payment payment = create(
            orderId,
            userEntityId,
            convertCardType(cardType),
            cardNo,
            amount,
            LocalDateTime.now()
        );
        
        // 3. 결제 요청 명령 생성 (PG 요청에는 String userId 사용)
        String callbackUrl = generateCallbackUrl(orderId);
        PaymentRequestCommand command = new PaymentRequestCommand(
            userId,
            orderId,
            cardType,
            cardNo,
            amount,
            callbackUrl
        );
        
        // 4. PG 결제 요청 전송
        PaymentRequestResult result = paymentGateway.requestPayment(command);
        
        // 5. 결과 처리
        if (result instanceof PaymentRequestResult.Success success) {
            log.info("PG 결제 요청 성공. (orderId: {}, transactionKey: {})", orderId, success.transactionKey());
            return result;
        } else if (result instanceof PaymentRequestResult.Failure failure) {
            // 실패 분류
            PaymentFailureType failureType = PaymentFailureType.classify(failure.errorCode());
            if (failureType == PaymentFailureType.BUSINESS_FAILURE) {
                // 비즈니스 실패: 결제 상태를 FAILED로 변경
                toFailed(payment.getId(), failure.message(), LocalDateTime.now(), null);
            }
            // 외부 시스템 장애는 PENDING 상태 유지
            log.warn("PG 결제 요청 실패. (orderId: {}, errorCode: {}, message: {})",
                orderId, failure.errorCode(), failure.message());
            return result;
        }
        
        return result;
    }
    
    /**
     * 결제 상태를 조회합니다.
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @return 결제 상태
     */
    @Transactional(readOnly = true)
    public PaymentStatus getPaymentStatus(String userId, Long orderId) {
        return paymentGateway.getPaymentStatus(userId, orderId);
    }
    
    /**
     * PG 콜백을 처리합니다.
     *
     * @param orderId 주문 ID
     * @param transactionKey 트랜잭션 키
     * @param status 결제 상태
     * @param reason 실패 사유 (실패 시)
     */
    @Transactional
    public void handleCallback(Long orderId, String transactionKey, PaymentStatus status, String reason) {
        Optional<Payment> paymentOpt = getPaymentByOrderId(orderId);
        if (paymentOpt.isEmpty()) {
            log.warn("콜백 처리 시 결제를 찾을 수 없습니다. (orderId: {})", orderId);
            return;
        }
        
        Payment payment = paymentOpt.get();
        
        if (status == PaymentStatus.SUCCESS) {
            toSuccess(payment.getId(), LocalDateTime.now(), transactionKey);
            log.info("결제 콜백 처리 완료: SUCCESS. (orderId: {}, transactionKey: {})", orderId, transactionKey);
        } else if (status == PaymentStatus.FAILED) {
            toFailed(payment.getId(), reason != null ? reason : "결제 실패", LocalDateTime.now(), transactionKey);
            log.warn("결제 콜백 처리 완료: FAILED. (orderId: {}, transactionKey: {}, reason: {})",
                orderId, transactionKey, reason);
        } else {
            // PENDING 상태: 상태 유지
            log.debug("결제 콜백 처리: PENDING 상태 유지. (orderId: {}, transactionKey: {})", orderId, transactionKey);
        }
    }
    
    /**
     * 타임아웃 후 결제 상태를 복구합니다.
     * <p>
     * 타임아웃 발생 후 실제 결제 상태를 확인하여 결제 상태를 업데이트합니다.
     * </p>
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @param delayDuration 대기 시간 (PG 처리 시간 고려)
     */
    public void recoverAfterTimeout(String userId, Long orderId, Duration delayDuration) {
        try {
            // 잠시 대기 후 상태 확인 (PG 처리 시간 고려)
            if (delayDuration != null && !delayDuration.isZero()) {
                Thread.sleep(delayDuration.toMillis());
            }
            
            // 결제 상태 조회
            PaymentStatus status = getPaymentStatus(userId, orderId);
            Optional<Payment> paymentOpt = getPaymentByOrderId(orderId);
            
            if (paymentOpt.isEmpty()) {
                log.warn("복구 시 결제를 찾을 수 없습니다. (orderId: {})", orderId);
                return;
            }
            
            Payment payment = paymentOpt.get();
            
            if (status == PaymentStatus.SUCCESS) {
                toSuccess(payment.getId(), LocalDateTime.now(), null);
                log.info("타임아웃 후 상태 확인 완료: SUCCESS. (orderId: {})", orderId);
            } else if (status == PaymentStatus.FAILED) {
                toFailed(payment.getId(), "타임아웃 후 상태 확인 실패", LocalDateTime.now(), null);
                log.warn("타임아웃 후 상태 확인 완료: FAILED. (orderId: {})", orderId);
            } else {
                // PENDING 상태: 상태 유지
                log.debug("타임아웃 후 상태 확인: PENDING 상태 유지. (orderId: {})", orderId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("타임아웃 후 상태 확인 중 인터럽트 발생. (orderId: {})", orderId);
        } catch (Exception e) {
            log.error("타임아웃 후 상태 확인 중 오류 발생. (orderId: {})", orderId, e);
        }
    }
    
    /**
     * 타임아웃 후 결제 상태를 복구합니다 (기본 대기 시간: 1초).
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     */
    public void recoverAfterTimeout(String userId, Long orderId) {
        recoverAfterTimeout(userId, orderId, Duration.ofSeconds(1));
    }
    
    // 내부 헬퍼 메서드들
    
    private CardType convertCardType(String cardType) {
        try {
            return CardType.valueOf(cardType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                String.format("잘못된 카드 타입입니다. (cardType: %s)", cardType));
        }
    }
    
    private String generateCallbackUrl(Long orderId) {
        return String.format("%s/api/v1/orders/%d/callback", callbackBaseUrl, orderId);
    }
    
    /**
     * 카드 번호 유효성 검증을 수행합니다.
     *
     * @param cardNo 카드 번호
     * @throws CoreException 유효하지 않은 카드 번호인 경우
     */
    private void validateCardNo(String cardNo) {
        if (cardNo == null || cardNo.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 번호는 필수입니다.");
        }
        
        // 공백/하이픈 제거 및 정규화
        String normalized = cardNo.replaceAll("[\\s-]", "");
        
        // 길이 검증 (13-19자리)
        if (normalized.length() < 13 || normalized.length() > 19) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                String.format("유효하지 않은 카드 번호 길이입니다. (길이: %d, 요구사항: 13-19자리)", normalized.length()));
        }
        
        // 숫자만 포함하는지 검증
        if (!normalized.matches("\\d+")) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 번호는 숫자만 포함해야 합니다.");
        }
        
        // Luhn 알고리즘 체크섬 검증
        if (!isValidLuhn(normalized)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "유효하지 않은 카드 번호입니다. (Luhn 알고리즘 검증 실패)");
        }
    }
    
    /**
     * Luhn 알고리즘을 사용하여 카드 번호의 체크섬을 검증합니다.
     *
     * @param cardNo 정규화된 카드 번호 (숫자만 포함)
     * @return 유효한 경우 true, 그렇지 않으면 false
     */
    private boolean isValidLuhn(String cardNo) {
        int sum = 0;
        boolean alternate = false;
        
        // 오른쪽에서 왼쪽으로 순회
        for (int i = cardNo.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(cardNo.charAt(i));
            
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit = (digit % 10) + 1;
                }
            }
            
            sum += digit;
            alternate = !alternate;
        }
        
        return (sum % 10) == 0;
    }
}

