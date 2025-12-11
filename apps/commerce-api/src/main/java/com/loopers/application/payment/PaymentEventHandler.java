package com.loopers.application.payment;

import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentGateway;
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
            if (paidAmount == 0) {
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
                                // PaymentRequested 이벤트에 포함된 totalAmount 사용
                                // 쿠폰 적용은 별도 이벤트 핸들러에서 처리되므로, 
                                // Payment 생성 시점의 totalAmount를 사용
                                Long paidAmount = event.totalAmount() - event.usedPointAmount();

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

                                // PG 결제 요청
                                PaymentRequestResult result = paymentGateway.requestPayment(command);

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

