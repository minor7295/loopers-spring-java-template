package com.loopers.domain.payment;

/**
 * 결제 도메인 이벤트 발행 인터페이스.
 * <p>
 * DIP를 준수하여 도메인 레이어에서 이벤트 발행 인터페이스를 정의합니다.
 * 구현은 인프라 레이어에서 제공됩니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface PaymentEventPublisher {

    /**
     * 결제 완료 이벤트를 발행합니다.
     *
     * @param event 결제 완료 이벤트
     */
    void publish(PaymentEvent.PaymentCompleted event);

    /**
     * 결제 실패 이벤트를 발행합니다.
     *
     * @param event 결제 실패 이벤트
     */
    void publish(PaymentEvent.PaymentFailed event);

    /**
     * 결제 요청 이벤트를 발행합니다.
     *
     * @param event 결제 요청 이벤트
     */
    void publish(PaymentEvent.PaymentRequested event);
}
