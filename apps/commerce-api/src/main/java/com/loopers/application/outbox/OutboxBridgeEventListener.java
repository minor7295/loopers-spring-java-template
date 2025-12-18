package com.loopers.application.outbox;

import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.product.ProductEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Outbox Bridge Event Listener.
 * <p>
 * ApplicationEvent를 구독하여 외부 시스템(Kafka)으로 전송해야 하는 이벤트를
 * Transactional Outbox Pattern을 통해 Outbox에 저장합니다.
 * </p>
 * <p>
 * <b>표준 패턴:</b>
 * <ul>
 *   <li>EventPublisher는 ApplicationEvent만 발행 (단일 책임)</li>
 *   <li>이 컴포넌트가 ApplicationEvent를 구독하여 Outbox에 저장 (관심사 분리)</li>
 *   <li>트랜잭션 커밋 후(AFTER_COMMIT) 처리하여 에러 격리</li>
 * </ul>
 * </p>
 * <p>
 * <b>처리 이벤트:</b>
 * <ul>
 *   <li><b>LikeEvent:</b> LikeAdded, LikeRemoved → like-events</li>
 *   <li><b>OrderEvent:</b> OrderCreated → order-events</li>
 *   <li><b>ProductEvent:</b> ProductViewed → product-events</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxBridgeEventListener {

    private final OutboxEventService outboxEventService;

    /**
     * LikeAdded 이벤트를 Outbox에 저장합니다.
     *
     * @param event LikeAdded 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLikeAdded(LikeEvent.LikeAdded event) {
        try {
            outboxEventService.saveEvent(
                "LikeAdded",
                event.productId().toString(),
                "Product",
                event,
                "like-events",
                event.productId().toString()
            );
            log.debug("LikeAdded 이벤트를 Outbox에 저장: productId={}", event.productId());
        } catch (Exception e) {
            log.error("LikeAdded 이벤트 Outbox 저장 실패: productId={}", event.productId(), e);
            // 외부 시스템 전송 실패는 내부 처리에 영향 없음
        }
    }

    /**
     * LikeRemoved 이벤트를 Outbox에 저장합니다.
     *
     * @param event LikeRemoved 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLikeRemoved(LikeEvent.LikeRemoved event) {
        try {
            outboxEventService.saveEvent(
                "LikeRemoved",
                event.productId().toString(),
                "Product",
                event,
                "like-events",
                event.productId().toString()
            );
            log.debug("LikeRemoved 이벤트를 Outbox에 저장: productId={}", event.productId());
        } catch (Exception e) {
            log.error("LikeRemoved 이벤트 Outbox 저장 실패: productId={}", event.productId(), e);
            // 외부 시스템 전송 실패는 내부 처리에 영향 없음
        }
    }

    /**
     * OrderCreated 이벤트를 Outbox에 저장합니다.
     *
     * @param event OrderCreated 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        try {
            outboxEventService.saveEvent(
                "OrderCreated",
                event.orderId().toString(),
                "Order",
                event,
                "order-events",
                event.orderId().toString()
            );
            log.debug("OrderCreated 이벤트를 Outbox에 저장: orderId={}", event.orderId());
        } catch (Exception e) {
            log.error("OrderCreated 이벤트 Outbox 저장 실패: orderId={}", event.orderId(), e);
            // 외부 시스템 전송 실패는 내부 처리에 영향 없음
        }
    }

    /**
     * ProductViewed 이벤트를 Outbox에 저장합니다.
     *
     * @param event ProductViewed 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProductViewed(ProductEvent.ProductViewed event) {
        try {
            outboxEventService.saveEvent(
                "ProductViewed",
                event.productId().toString(),
                "Product",
                event,
                "product-events",
                event.productId().toString()
            );
            log.debug("ProductViewed 이벤트를 Outbox에 저장: productId={}", event.productId());
        } catch (Exception e) {
            log.error("ProductViewed 이벤트 Outbox 저장 실패: productId={}", event.productId(), e);
            // 외부 시스템 전송 실패는 내부 처리에 영향 없음
        }
    }
}
