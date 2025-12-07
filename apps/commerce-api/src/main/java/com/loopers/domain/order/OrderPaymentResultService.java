package com.loopers.domain.order;

import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 결제 결과 처리 도메인 서비스.
 * <p>
 * 결제 결과에 따라 주문 상태를 처리합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentResultService {
    
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
     * @param paymentStatus 결제 상태 (도메인 모델)
     * @param transactionKey 트랜잭션 키
     * @param reason 실패 사유 (실패 시)
     * @return 업데이트 성공 여부 (true: 성공, false: 실패)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean updateByPaymentStatus(
        Long orderId,
        PaymentStatus paymentStatus,
        String transactionKey,
        String reason
    ) {
        try {
            Order order = orderRepository.findById(orderId)
                .orElse(null);
            
            if (order == null) {
                log.warn("주문 상태 업데이트 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
                return false;
            }
            
            // 이미 완료되거나 취소된 주문인 경우 처리하지 않음 (정상적인 경우이므로 true 반환)
            if (order.getStatus() == OrderStatus.COMPLETED) {
                log.info("이미 완료된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return true;
            }
            
            if (order.getStatus() == OrderStatus.CANCELED) {
                log.info("이미 취소된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return true;
            }
            
            if (paymentStatus == PaymentStatus.SUCCESS) {
                // 결제 성공: 주문 완료
                order.complete();
                orderRepository.save(order);
                log.info("결제 상태 확인 결과, 주문 상태를 COMPLETED로 업데이트했습니다. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
                return true;
            } else if (paymentStatus == PaymentStatus.FAILED) {
                // 결제 실패: 주문 취소 및 리소스 원복
                User user = userRepository.findById(order.getUserId());
                if (user == null) {
                    log.warn("주문 상태 업데이트 시 사용자를 찾을 수 없습니다. (orderId: {}, userId: {})",
                        orderId, order.getUserId());
                    return false;
                }
                orderCancellationService.cancel(order, user);
                log.info("결제 상태 확인 결과, 주문 상태를 CANCELED로 업데이트했습니다. (orderId: {}, transactionKey: {}, reason: {})",
                    orderId, transactionKey, reason);
                return true;
            } else {
                // PENDING 상태: 아직 처리 중 (정상적인 경우이므로 true 반환)
                log.info("결제 상태 확인 결과, 아직 처리 중입니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
                return true;
            }
        } catch (Exception e) {
            log.error("주문 상태 업데이트 중 오류 발생. (orderId: {})", orderId, e);
            return false;
        }
    }
}

