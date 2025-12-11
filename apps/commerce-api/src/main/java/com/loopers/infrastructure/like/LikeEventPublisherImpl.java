package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.like.LikeEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * LikeEventPublisher 인터페이스의 구현체.
 * <p>
 * Spring ApplicationEventPublisher를 사용하여 좋아요 이벤트를 발행합니다.
 * DIP를 준수하여 도메인 인터페이스를 구현합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class LikeEventPublisherImpl implements LikeEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(LikeEvent.LikeAdded event) {
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publish(LikeEvent.LikeRemoved event) {
        applicationEventPublisher.publishEvent(event);
    }
}

