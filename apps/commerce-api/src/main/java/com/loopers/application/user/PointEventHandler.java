package com.loopers.application.user;

import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.PointEvent;
import com.loopers.domain.user.PointEventPublisher;
import com.loopers.domain.user.User;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 이벤트 핸들러.
 * <p>
 * 주문 생성 이벤트를 받아 포인트 사용 처리를 수행하고, 주문 취소 이벤트를 받아 포인트 환불 처리를 수행하는 애플리케이션 로직을 처리합니다.
 * </p>
 * <p>
 * <b>DDD/EDA 관점:</b>
 * <ul>
 *   <li><b>책임 분리:</b> UserService는 사용자 도메인 비즈니스 로직, PointEventHandler는 이벤트 처리 로직</li>
 *   <li><b>이벤트 핸들러:</b> 이벤트를 받아서 처리하는 역할을 명확히 나타냄</li>
 *   <li><b>느슨한 결합:</b> PurchasingFacade는 UserService를 직접 참조하지 않고 이벤트로 처리</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointEventHandler {

    private final UserService userService;
    private final PointEventPublisher pointEventPublisher;

    /**
     * 주문 생성 이벤트를 처리하여 포인트를 차감합니다.
     * <p>
     * OrderEvent.OrderCreated를 구독하여 포인트 차감 Command를 실행합니다.
     * </p>
     *
     * @param event 주문 생성 이벤트
     */
    @Transactional
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        // 포인트 사용량이 없는 경우 처리하지 않음
        if (event.usedPointAmount() == null || event.usedPointAmount() == 0) {
            log.debug("포인트 사용량이 없어 포인트 차감 처리를 건너뜁니다. (orderId: {})", event.orderId());
            return;
        }

        try {
            // 사용자 조회 (비관적 락 사용)
            User user = userService.getUserById(event.userId());
            
            // 포인트 잔액 검증
            Long userPointBalance = user.getPointValue();
            if (userPointBalance < event.usedPointAmount()) {
                String failureReason = String.format("포인트가 부족합니다. (현재 잔액: %d, 사용 요청 금액: %d)", 
                        userPointBalance, event.usedPointAmount());
                log.error("포인트가 부족합니다. (orderId: {}, userId: {}, 현재 잔액: {}, 사용 요청 금액: {})",
                        event.orderId(), event.userId(), userPointBalance, event.usedPointAmount());
                
                // 포인트 사용 실패 이벤트 발행
                pointEventPublisher.publish(PointEvent.PointUsedFailed.of(
                        event.orderId(),
                        event.userId(),
                        event.usedPointAmount(),
                        failureReason
                ));
                
                throw new CoreException(ErrorType.BAD_REQUEST, failureReason);
            }

            // ✅ OrderEvent.OrderCreated를 구독하여 포인트 차감 Command 실행
            DeductPointCommand command = new DeductPointCommand(event.userId(), event.usedPointAmount());
            user.deductPoint(Point.of(command.usedPointAmount()));
            userService.save(user);

            log.info("포인트 차감 처리 완료. (orderId: {}, userId: {}, usedPointAmount: {})",
                    event.orderId(), event.userId(), event.usedPointAmount());
        } catch (CoreException e) {
            // CoreException은 이미 이벤트가 발행되었거나 처리되었으므로 그대로 던짐
            throw e;
        } catch (Exception e) {
            // 예상치 못한 오류 발생 시 실패 이벤트 발행
            String failureReason = e.getMessage() != null ? e.getMessage() : "포인트 차감 처리 중 오류 발생";
            log.error("포인트 차감 처리 중 오류 발생. (orderId: {}, userId: {}, usedPointAmount: {})",
                    event.orderId(), event.userId(), event.usedPointAmount(), e);
            
            pointEventPublisher.publish(PointEvent.PointUsedFailed.of(
                    event.orderId(),
                    event.userId(),
                    event.usedPointAmount(),
                    failureReason
            ));
            
            throw e;
        }
    }

    /**
     * 주문 취소 이벤트를 처리하여 포인트를 환불합니다.
     * <p>
     * 환불할 포인트 금액이 0보다 큰 경우에만 포인트 환불 처리를 수행합니다.
     * </p>
     * <p>
     * <b>동시성 제어:</b>
     * <ul>
     *   <li><b>비관적 락 사용:</b> 포인트 환불 시 동시성 제어를 위해 getUserForUpdate 사용</li>
     * </ul>
     * </p>
     *
     * @param event 주문 취소 이벤트
     */
    @Transactional
    public void handleOrderCanceled(OrderEvent.OrderCanceled event) {
        // 환불할 포인트 금액이 없는 경우 처리하지 않음
        if (event.refundPointAmount() == null || event.refundPointAmount() == 0) {
            log.debug("환불할 포인트 금액이 없어 포인트 환불 처리를 건너뜁니다. (orderId: {})", event.orderId());
            return;
        }

        try {
            // ✅ Deadlock 방지: User 락을 먼저 획득하여 createOrder와 동일한 락 획득 순서 보장
            User user = userService.getUserById(event.userId());
            if (user == null) {
                log.warn("주문 취소 이벤트 처리 시 사용자를 찾을 수 없습니다. (orderId: {}, userId: {})",
                        event.orderId(), event.userId());
                return;
            }

            // 비관적 락을 사용하여 사용자 조회 (포인트 환불 시 동시성 제어)
            User lockedUser = userService.getUserForUpdate(user.getUserId());

            // 포인트 환불
            lockedUser.receivePoint(Point.of(event.refundPointAmount()));
            userService.save(lockedUser);

            log.info("주문 취소로 인한 포인트 환불 완료. (orderId: {}, userId: {}, refundPointAmount: {})",
                    event.orderId(), event.userId(), event.refundPointAmount());
        } catch (Exception e) {
            log.error("포인트 환불 처리 중 오류 발생. (orderId: {}, userId: {}, refundPointAmount: {})",
                    event.orderId(), event.userId(), event.refundPointAmount(), e);
            throw e;
        }
    }
}

