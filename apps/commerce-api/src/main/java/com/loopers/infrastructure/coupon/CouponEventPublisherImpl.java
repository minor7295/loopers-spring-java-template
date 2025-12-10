package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponEvent;
import com.loopers.domain.coupon.CouponEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * CouponEventPublisher 인터페이스의 구현체.
 * <p>
 * Spring ApplicationEventPublisher를 사용하여 쿠폰 이벤트를 발행합니다.
 * DIP를 준수하여 도메인 인터페이스를 구현합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class CouponEventPublisherImpl implements CouponEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(CouponEvent.CouponApplied event) {
        applicationEventPublisher.publishEvent(event);
    }
}

