package com.loopers.domain.order;

import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.paymentgateway.PaymentGatewayDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 상태 업데이트 도메인 서비스.
 * <p>
 * 결제 상태에 따라 주문 상태를 업데이트합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusUpdater {
    
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final OrderCancellationService orderCancellationService;
    
    /**
     * 결제 상태에 따라 주문 상태를 업데이트합니다.
     * <p>
     * 별도 트랜잭션으로 실행하여 외부 시스템 호출과 독립적으로 처리합니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param status 결제 상태
     * @param transactionKey 트랜잭션 키
     * @param reason 실패 사유 (실패 시)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateByPaymentStatus(
        Long orderId,
        PaymentGatewayDto.TransactionStatus status,
        String transactionKey,
        String reason
    ) {
        try {
            Order order = orderRepository.findById(orderId)
                .orElse(null);
            
            if (order == null) {
                log.warn("주문 상태 업데이트 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
                return;
            }
            
            // 이미 완료되거나 취소된 주문인 경우 처리하지 않음
            if (order.getStatus() == OrderStatus.COMPLETED) {
                log.info("이미 완료된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return;
            }
            
            if (order.getStatus() == OrderStatus.CANCELED) {
                log.info("이미 취소된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return;
            }
            
            if (status == PaymentGatewayDto.TransactionStatus.SUCCESS) {
                // 결제 성공: 주문 완료
                order.complete();
                orderRepository.save(order);
                log.info("결제 상태 확인 결과, 주문 상태를 COMPLETED로 업데이트했습니다. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
            } else if (status == PaymentGatewayDto.TransactionStatus.FAILED) {
                // 결제 실패: 주문 취소 및 리소스 원복
                User user = userRepository.findById(order.getUserId());
                if (user == null) {
                    log.warn("주문 상태 업데이트 시 사용자를 찾을 수 없습니다. (orderId: {}, userId: {})",
                        orderId, order.getUserId());
                    return;
                }
                orderCancellationService.cancel(order, user);
                log.info("결제 상태 확인 결과, 주문 상태를 CANCELED로 업데이트했습니다. (orderId: {}, transactionKey: {}, reason: {})",
                    orderId, transactionKey, reason);
            } else {
                // PENDING 상태: 아직 처리 중
                log.info("결제 상태 확인 결과, 아직 처리 중입니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
            }
        } catch (Exception e) {
            log.error("주문 상태 업데이트 중 오류 발생. (orderId: {})", orderId, e);
            // 예외 발생 시에도 로그만 기록 (나중에 스케줄러로 복구 가능)
        }
    }
}

