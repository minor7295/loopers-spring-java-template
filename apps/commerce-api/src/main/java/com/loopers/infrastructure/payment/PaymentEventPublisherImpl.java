package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * PaymentEventPublisher 인터페이스의 구현체.
 * <p>
 * Spring ApplicationEventPublisher를 사용하여 결제 이벤트를 발행합니다.
 * DIP를 준수하여 도메인 인터페이스를 구현합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class PaymentEventPublisherImpl implements PaymentEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(PaymentEvent.PaymentCompleted event) {
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publish(PaymentEvent.PaymentFailed event) {
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publish(PaymentEvent.PaymentRequested event) {
        applicationEventPublisher.publishEvent(event);
    }
}
