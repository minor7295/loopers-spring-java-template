package com.loopers.infrastructure.user;

import com.loopers.domain.user.PointEvent;
import com.loopers.domain.user.PointEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * PointEventPublisher 인터페이스의 구현체.
 * <p>
 * Spring ApplicationEventPublisher를 사용하여 포인트 이벤트를 발행합니다.
 * DIP를 준수하여 도메인 인터페이스를 구현합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class PointEventPublisherImpl implements PointEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(PointEvent.PointUsed event) {
        applicationEventPublisher.publishEvent(event);
    }
}

