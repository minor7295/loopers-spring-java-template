package com.loopers.application.purchasing;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 결과에 따른 주문 상태 업데이트 이벤트 리스너.
 * <p>
 * 결제 완료/실패 이벤트를 받아 주문 상태를 업데이트합니다.
 * </p>
 * <p>
 * <b>트랜잭션 전략:</b>
 * <ul>
 *   <li><b>AFTER_COMMIT:</b> 결제 트랜잭션이 커밋된 후에 실행되어 결제와 주문 처리를 분리</li>
 *   <li><b>REQUIRES_NEW:</b> 별도 트랜잭션으로 실행하여 주문 처리 실패가 결제에 영향을 주지 않도록 함</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusUpdateEventListener {

    private final OrderService orderService;
    private final UserService userService;
    private final PurchasingFacade purchasingFacade;

    /**
     * 결제 완료 이벤트를 처리하여 주문 상태를 COMPLETED로 업데이트합니다.
     * <p>
     * <b>동기 처리 이유:</b>
     * 결제 완료 후 주문 상태를 COMPLETED로 변경하는 것은 핵심 비즈니스 로직입니다.
     * 비동기 처리 시 결제 완료와 주문 완료 사이의 시간 차이로 인한 데이터 불일치를 방지하기 위해 동기 처리합니다.
     * </p>
     *
     * @param event 결제 완료 이벤트
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentCompleted(PaymentEvent.PaymentCompleted event) {
        try {
            Order order = orderService.findById(event.orderId()).orElse(null);
            if (order == null) {
                log.warn("결제 완료 이벤트 처리 시 주문을 찾을 수 없습니다. (orderId: {})", event.orderId());
                return;
            }

            // 이미 완료된 주문인 경우 처리하지 않음
            if (order.getStatus() == OrderStatus.COMPLETED) {
                log.debug("이미 완료된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", event.orderId());
                return;
            }

            // 주문 완료 처리
            orderService.completeOrder(event.orderId());
            log.info("결제 완료로 인한 주문 상태 업데이트 완료. (orderId: {}, transactionKey: {})",
                event.orderId(), event.transactionKey());
        } catch (Exception e) {
            log.error("결제 완료 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
        }
    }

    /**
     * 결제 실패 이벤트를 처리하여 주문을 취소하고 리소스를 원복합니다.
     * <p>
     * <b>비동기 처리 이유:</b>
     * 결제 실패 시 주문 취소 및 리소스 원복은 부가 로직이며, 외부 시스템(재고, 포인트)과의 I/O가 포함됩니다.
     * 비동기 처리로 결제 처리 성능에 영향을 주지 않습니다.
     * </p>
     *
     * @param event 결제 실패 이벤트
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentFailed(PaymentEvent.PaymentFailed event) {
        try {
            Order order = orderService.findById(event.orderId()).orElse(null);
            if (order == null) {
                log.warn("결제 실패 이벤트 처리 시 주문을 찾을 수 없습니다. (orderId: {})", event.orderId());
                return;
            }

            // 이미 취소된 주문인 경우 처리하지 않음
            if (order.getStatus() == OrderStatus.CANCELED) {
                log.debug("이미 취소된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", event.orderId());
                return;
            }

            // 사용자 조회
            User user = userService.findById(order.getUserId());
            if (user == null) {
                log.warn("결제 실패 이벤트 처리 시 사용자를 찾을 수 없습니다. (orderId: {}, userId: {})",
                    event.orderId(), order.getUserId());
                return;
            }

            // 주문 취소 및 리소스 원복 (취소 사유 포함)
            purchasingFacade.cancelOrderWithReason(order, user, event.reason());
            log.info("결제 실패로 인한 주문 취소 완료. (orderId: {}, reason: {})",
                event.orderId(), event.reason());
        } catch (Exception e) {
            log.error("결제 실패 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
        }
    }
}
