package com.loopers.domain.order;

/**
 * 주문 도메인 이벤트 발행 인터페이스.
 * <p>
 * DIP를 준수하여 도메인 레이어에서 이벤트 발행 인터페이스를 정의합니다.
 * 구현은 인프라 레이어에서 제공됩니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface OrderEventPublisher {

    /**
     * 주문 생성 이벤트를 발행합니다.
     *
     * @param event 주문 생성 이벤트
     */
    void publish(OrderEvent.OrderCreated event);

    /**
     * 주문 완료 이벤트를 발행합니다.
     *
     * @param event 주문 완료 이벤트
     */
    void publish(OrderEvent.OrderCompleted event);

    /**
     * 주문 취소 이벤트를 발행합니다.
     *
     * @param event 주문 취소 이벤트
     */
    void publish(OrderEvent.OrderCanceled event);
}
