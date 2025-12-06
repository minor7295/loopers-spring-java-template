package com.loopers.application.purchasing;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.OrderCancellationService;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 실패 처리 서비스.
 * <p>
 * 결제 실패 시 주문 취소 및 리소스 원복을 처리합니다.
 * </p>
 * <p>
 * <b>트랜잭션 전략:</b>
 * <ul>
 *   <li>REQUIRES_NEW: 별도 트랜잭션으로 실행하여 외부 시스템 호출과 독립적으로 처리</li>
 *   <li>결제 실패 처리 중 오류가 발생해도 기존 주문 생성 트랜잭션에 영향을 주지 않음</li>
 *   <li>Self-invocation 문제 해결: 별도 서비스로 분리하여 Spring AOP 프록시가 정상적으로 적용되도록 함</li>
 * </ul>
 * </p>
 * <p>
 * <b>주의사항:</b>
 * <ul>
 *   <li>주문이 이미 취소되었거나 존재하지 않는 경우 로그만 기록합니다.</li>
 *   <li>결제 실패 처리 중 오류 발생 시에도 로그만 기록합니다.</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailureHandler {
    
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderCancellationService orderCancellationService;
    
    /**
     * 결제 실패 시 주문 취소 및 리소스 원복을 처리합니다.
     * <p>
     * 결제 요청이 실패한 경우, 이미 생성된 주문을 취소하고
     * 차감된 포인트를 환불하며 재고를 원복합니다.
     * </p>
     * <p>
     * <b>처리 내용:</b>
     * <ul>
     *   <li>주문 상태를 CANCELED로 변경</li>
     *   <li>차감된 포인트 환불</li>
     *   <li>차감된 재고 원복</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID (로그인 ID)
     * @param orderId 주문 ID
     * @param errorCode 오류 코드
     * @param errorMessage 오류 메시지
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(String userId, Long orderId, String errorCode, String errorMessage) {
        try {
            // 사용자 조회
            User user = userRepository.findByUserId(userId);
            
            if (user == null) {
                log.warn("결제 실패 처리 시 사용자를 찾을 수 없습니다. (userId: {}, orderId: {})", userId, orderId);
                return;
            }
            
            // 주문 조회
            Order order = orderRepository.findById(orderId)
                .orElse(null);
            
            if (order == null) {
                log.warn("결제 실패 처리 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
                return;
            }
            
            // 이미 취소된 주문인 경우 처리하지 않음
            if (order.getStatus() == OrderStatus.CANCELED) {
                log.info("이미 취소된 주문입니다. 결제 실패 처리를 건너뜁니다. (orderId: {})", orderId);
                return;
            }
            
            // 주문 취소 및 리소스 원복
            orderCancellationService.cancel(order, user);
            
            log.info("결제 실패로 인한 주문 취소 완료. (orderId: {}, errorCode: {}, errorMessage: {})",
                orderId, errorCode, errorMessage);
        } catch (Exception e) {
            // 결제 실패 처리 중 오류 발생 시에도 로그만 기록
            // 이미 주문은 생성되어 있으므로, 나중에 수동으로 처리할 수 있도록 로그 기록
            log.error("결제 실패 처리 중 오류 발생. (orderId: {}, errorCode: {})",
                orderId, errorCode, e);
        }
    }
}

