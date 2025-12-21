package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductEvent;
import com.loopers.domain.product.ProductEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * ProductEventPublisher 인터페이스의 구현체.
 * <p>
 * Spring ApplicationEventPublisher를 사용하여 상품 이벤트를 발행합니다.
 * DIP를 준수하여 도메인 인터페이스를 구현합니다.
 * </p>
 * <p>
 * <b>표준 패턴:</b>
 * <ul>
 *   <li>ApplicationEvent만 발행 (단일 책임 원칙)</li>
 *   <li>Kafka 전송은 OutboxBridgeEventListener가 처리 (관심사 분리)</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class ProductEventPublisherImpl implements ProductEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(ProductEvent.ProductViewed event) {
        applicationEventPublisher.publishEvent(event);
    }
}
