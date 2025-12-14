package com.loopers.interfaces.event.product;

import com.loopers.application.product.ProductEventHandler;
import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.order.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 상품 이벤트 리스너.
 * <p>
 * 좋아요 추가/취소 이벤트와 주문 생성/취소 이벤트를 받아서 상품의 좋아요 수 및 재고를 업데이트하는 인터페이스 레이어의 어댑터입니다.
 * </p>
 * <p>
 * <b>레이어 역할:</b>
 * <ul>
 *   <li><b>인터페이스 레이어:</b> 외부 이벤트(도메인 이벤트)를 받아서 애플리케이션 핸들러를 호출하는 어댑터</li>
 *   <li><b>비즈니스 로직 없음:</b> 단순히 이벤트를 받아서 애플리케이션 핸들러를 호출하는 역할만 수행</li>
 * </ul>
 * </p>
 * <p>
 * <b>EDA 원칙:</b>
 * <ul>
 *   <li><b>느슨한 결합:</b> HeartFacade는 이 리스너의 존재를 모름</li>
 *   <li><b>비동기 처리:</b> @Async로 집계 처리를 비동기로 실행</li>
 *   <li><b>이벤트 기반:</b> 좋아요 추가/취소 이벤트를 구독하여 상품의 좋아요 수 업데이트</li>
 * </ul>
 * </p>
 * <p>
 * <b>집계 전략:</b>
 * <ul>
 *   <li><b>이벤트 기반 실시간 집계:</b> 좋아요 추가/취소 시 즉시 Product.likeCount 업데이트</li>
 *   <li><b>Strong Consistency:</b> 이벤트 기반으로 실시간 반영</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventListener {

    private final ProductEventHandler productEventHandler;

    /**
     * 좋아요 추가 이벤트를 처리합니다.
     * <p>
     * 트랜잭션 커밋 후 비동기로 실행되어 상품의 좋아요 수를 증가시킵니다.
     * </p>
     *
     * @param event 좋아요 추가 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(LikeEvent.LikeAdded event) {
        try {
            productEventHandler.handleLikeAdded(event);
        } catch (Exception e) {
            log.error("좋아요 추가 이벤트 처리 중 오류 발생: productId={}, userId={}", 
                event.productId(), event.userId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }

    /**
     * 좋아요 취소 이벤트를 처리합니다.
     * <p>
     * 트랜잭션 커밋 후 비동기로 실행되어 상품의 좋아요 수를 감소시킵니다.
     * </p>
     *
     * @param event 좋아요 취소 이벤트
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(LikeEvent.LikeRemoved event) {
        try {
            productEventHandler.handleLikeRemoved(event);
        } catch (Exception e) {
            log.error("좋아요 취소 이벤트 처리 중 오류 발생: productId={}, userId={}", 
                event.productId(), event.userId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }

    /**
     * 주문 생성 이벤트를 처리합니다.
     * <p>
     * 주문 생성과 같은 트랜잭션 내에서 동기적으로 실행되어 재고를 차감합니다.
     * 재고 차감은 민감한 영역이므로 하나의 트랜잭션으로 실행되어야 합니다.
     * </p>
     *
     * @param event 주문 생성 이벤트
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        try {
            productEventHandler.handleOrderCreated(event);
        } catch (Exception e) {
            log.error("주문 생성 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            // 재고 차감 실패 시 주문 생성도 롤백되어야 하므로 예외를 다시 던짐
            throw e;
        }
    }

    /**
     * 주문 취소 이벤트를 처리합니다.
     * <p>
     * 주문 취소와 같은 트랜잭션 내에서 동기적으로 실행되어 재고를 원복합니다.
     * 재고 원복은 민감한 영역이므로 하나의 트랜잭션으로 실행되어야 합니다.
     * </p>
     *
     * @param event 주문 취소 이벤트
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleOrderCanceled(OrderEvent.OrderCanceled event) {
        try {
            productEventHandler.handleOrderCanceled(event);
        } catch (Exception e) {
            log.error("주문 취소 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            // 재고 원복 실패 시 주문 취소도 롤백되어야 하므로 예외를 다시 던짐
            throw e;
        }
    }
}

