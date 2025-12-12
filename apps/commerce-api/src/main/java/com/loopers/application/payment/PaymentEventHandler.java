package com.loopers.application.payment;

import com.loopers.domain.coupon.CouponEvent;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PaymentRequestResult;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * 결제 이벤트 핸들러.
 * <p>
 * 결제 요청 이벤트를 받아 Payment 생성 및 PG 결제 요청 처리를 수행하는 애플리케이션 로직을 처리합니다.
 * </p>
 * <p>
 * <b>DDD/EDA 관점:</b>
 * <ul>
 *   <li><b>책임 분리:</b> PaymentService는 결제 도메인 비즈니스 로직, PaymentEventHandler는 이벤트 처리 로직</li>
 *   <li><b>이벤트 핸들러:</b> 이벤트를 받아서 처리하는 역할을 명확히 나타냄</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventHandler {

    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;

    /**
     * 결제 요청 이벤트를 처리하여 Payment를 생성하고 PG 결제를 요청합니다.
     * <p>
     * 결제 금액이 0인 경우 PG 요청 없이 바로 완료 처리합니다.
     * </p>
     *
     * @param event 결제 요청 이벤트
     */
    @Transactional
    public void handlePaymentRequested(PaymentEvent.PaymentRequested event) {
        try {
            // Payment 생성
            CardType cardTypeEnum = (event.cardType() != null && !event.cardType().isBlank()) 
                    ? convertCardType(event.cardType()) 
                    : null;
            
            Payment payment = paymentService.create(
                    event.orderId(),
                    event.userEntityId(),
                    event.totalAmount(),
                    event.usedPointAmount(),
                    cardTypeEnum,
                    event.cardNo(),
                    LocalDateTime.now()
            );

            // 결제 금액이 0인 경우 (포인트+쿠폰으로 전액 결제)
            Long paidAmount = event.totalAmount() - event.usedPointAmount();
            if (paidAmount.equals(0L)) {
                // PG 요청 없이 바로 완료 (PaymentCompleted 이벤트 발행)
                paymentService.toSuccess(payment.getId(), LocalDateTime.now(), null);
                log.info("포인트+쿠폰으로 전액 결제 완료. (orderId: {})", event.orderId());
                return;
            }

            // PG 결제가 필요한 경우
            if (event.cardType() == null || event.cardType().isBlank() || 
                event.cardNo() == null || event.cardNo().isBlank()) {
                log.error("카드 정보가 없어 PG 결제를 진행할 수 없습니다. (orderId: {})", event.orderId());
                throw new CoreException(
                        ErrorType.BAD_REQUEST,
                        "포인트와 쿠폰만으로 결제할 수 없습니다. 카드 정보를 입력해주세요.");
            }

            // PG 결제 요청 (트랜잭션 커밋 후 별도 트랜잭션에서 처리)
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                // 쿠폰 할인이 적용된 후의 최신 Payment 정보 조회
                                // 쿠폰 할인이 적용되면 Payment.applyCouponDiscount에서 paidAmount가 재계산됨
                                Payment payment = paymentService.getPaymentByOrderId(event.orderId())
                                    .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                                        String.format("주문 ID에 해당하는 결제를 찾을 수 없습니다. (orderId: %d)", event.orderId())));
                                
                                // 이미 완료된 결제는 PG 요청 불필요
                                if (payment.isCompleted()) {
                                    log.info("결제가 이미 완료되어 PG 요청을 건너뜁니다. (orderId: {})", event.orderId());
                                    return;
                                }
                                
                                // 최신 paidAmount 사용 (쿠폰 할인 적용 후 금액)
                                Long paidAmount = payment.getPaidAmount();
                                
                                // paidAmount가 0이면 PG 요청 불필요 (이미 완료 처리됨)
                                if (paidAmount == 0L) {
                                    log.info("결제 금액이 0이어서 PG 요청을 건너뜁니다. (orderId: {})", event.orderId());
                                    return;
                                }

                                // ✅ PaymentEvent.PaymentRequested를 구독하여 결제 요청 Command 실행
                                String callbackUrl = generateCallbackUrl(event.orderId());
                                // ✅ RequestPaymentCommand (PaymentRequestCommand) 생성 및 실행
                                PaymentRequestCommand command = new PaymentRequestCommand(
                                    event.userId(),
                                    event.orderId(),
                                    event.cardType(),
                                    event.cardNo(),
                                    paidAmount,
                                    callbackUrl
                                );
                                // 도메인 계층으로 변환하여 PG 결제 요청
                                PaymentRequest paymentRequest = command.toPaymentRequest();
                                PaymentRequestResult result = paymentGateway.requestPayment(paymentRequest);

                                if (result instanceof PaymentRequestResult.Success success) {
                                    // 결제 성공: PaymentService.toSuccess가 PaymentCompleted 이벤트를 발행하고,
                                    // OrderEventHandler가 이를 받아 주문 상태를 COMPLETED로 변경
                                    paymentService.getPaymentByOrderId(event.orderId()).ifPresent(p -> {
                                        if (p.isPending()) {
                                            paymentService.toSuccess(p.getId(), LocalDateTime.now(), success.transactionKey());
                                        }
                                    });
                                    log.info("PG 결제 요청 성공. (orderId: {}, transactionKey: {})", 
                                            event.orderId(), success.transactionKey());
                                } else if (result instanceof PaymentRequestResult.Failure failure) {
                                    // PG 요청 실패: PaymentService.toFailed가 PaymentFailed 이벤트를 발행하고,
                                    // OrderEventHandler가 이를 받아 주문 취소 처리
                                    paymentService.getPaymentByOrderId(event.orderId()).ifPresent(p -> {
                                        if (p.isPending()) {
                                            paymentService.toFailed(p.getId(), failure.message(), 
                                                    LocalDateTime.now(), null);
                                        }
                                    });
                                    log.warn("PG 결제 요청 실패. (orderId: {}, errorCode: {}, message: {})", 
                                            event.orderId(), failure.errorCode(), failure.message());
                                }
                            } catch (Exception e) {
                                log.error("PG 결제 요청 중 예외 발생. 주문은 PENDING 상태로 유지됩니다. (orderId: {})",
                                        event.orderId(), e);
                            }
                        }
                    }
            );

            log.info("결제 요청 처리 완료. (orderId: {}, totalAmount: {}, usedPointAmount: {})",
                    event.orderId(), event.totalAmount(), event.usedPointAmount());
        } catch (Exception e) {
            log.error("결제 요청 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            throw e;
        }
    }

    /**
     * 쿠폰 적용 이벤트를 처리하여 결제 금액을 업데이트합니다.
     * <p>
     * 쿠폰 할인이 적용된 후 Order의 totalAmount가 업데이트되면,
     * Payment의 totalAmount도 동기화하기 위해 호출됩니다.
     * </p>
     * <p>
     * <b>EDA 원칙:</b>
     * <ul>
     *   <li><b>이벤트 구독:</b> CouponEvent.CouponApplied 이벤트를 구독하여 결제 도메인 상태 업데이트</li>
     *   <li><b>책임 분리:</b> CouponEventHandler는 쿠폰 도메인만 관리하고, 결제 동기화는 이 핸들러에서 처리</li>
     * </ul>
     * </p>
     *
     * @param event 쿠폰 적용 이벤트
     */
    @Transactional
    public void handleCouponApplied(CouponEvent.CouponApplied event) {
        try {
            // 결제 금액에 쿠폰 할인 적용
            paymentService.applyCouponDiscount(event.orderId(), event.discountAmount());
            
            log.info("쿠폰 할인 금액이 결제에 적용되었습니다. (orderId: {}, couponCode: {}, discountAmount: {})",
                    event.orderId(), event.couponCode(), event.discountAmount());
        } catch (CoreException e) {
            // 결제를 찾을 수 없는 경우는 로그만 기록 (정상적인 경우일 수 있음)
            if (e.getErrorType() == ErrorType.NOT_FOUND) {
                log.debug("쿠폰 적용 시 결제를 찾을 수 없습니다. (orderId: {}, couponCode: {})",
                        event.orderId(), event.couponCode());
            } else {
                log.error("쿠폰 적용 이벤트 처리 중 오류 발생. (orderId: {}, couponCode: {})",
                        event.orderId(), event.couponCode(), e);
            }
        } catch (Exception e) {
            log.error("쿠폰 적용 이벤트 처리 중 예상치 못한 오류 발생. (orderId: {}, couponCode: {})",
                    event.orderId(), event.couponCode(), e);
        }
    }

    /**
     * 카드 타입 문자열을 CardType enum으로 변환합니다.
     *
     * @param cardType 카드 타입 문자열
     * @return CardType enum
     */
    private CardType convertCardType(String cardType) {
        try {
            return CardType.valueOf(cardType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CoreException(
                    ErrorType.BAD_REQUEST,
                    String.format("잘못된 카드 타입입니다. (cardType: %s)", cardType));
        }
    }

    /**
     * 콜백 URL을 생성합니다.
     *
     * @param orderId 주문 ID
     * @return 콜백 URL
     */
    private String generateCallbackUrl(Long orderId) {
        return String.format("/api/v1/payments/callback?orderId=%d", orderId);
    }
}

