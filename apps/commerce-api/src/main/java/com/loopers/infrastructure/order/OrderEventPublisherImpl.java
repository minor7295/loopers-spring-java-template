package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.order.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * OrderEventPublisher 인터페이스의 구현체.
 * <p>
 * Spring ApplicationEventPublisher를 사용하여 주문 이벤트를 발행합니다.
 * DIP를 준수하여 도메인 인터페이스를 구현합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class OrderEventPublisherImpl implements OrderEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(OrderEvent.OrderCreated event) {
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publish(OrderEvent.OrderCompleted event) {
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publish(OrderEvent.OrderCanceled event) {
        applicationEventPublisher.publishEvent(event);
    }
}
