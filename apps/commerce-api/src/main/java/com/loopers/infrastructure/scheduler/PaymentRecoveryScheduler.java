package com.loopers.infrastructure.scheduler;

import com.loopers.application.purchasing.PurchasingFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.infrastructure.user.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 결제 상태 복구 스케줄러.
 * <p>
 * 콜백이 오지 않은 PENDING 상태의 주문들을 주기적으로 조회하여
 * PG 시스템의 결제 상태 확인 API를 통해 상태를 복구합니다.
 * </p>
 * <p>
 * <b>동작 원리:</b>
 * <ol>
 *   <li>주기적으로 실행 (기본: 1분마다)</li>
 *   <li>PENDING 상태인 주문들을 조회</li>
 *   <li>각 주문에 대해 PG 결제 상태 확인 API 호출</li>
 *   <li>결제 상태에 따라 주문 상태 업데이트</li>
 * </ol>
 * </p>
 * <p>
 * <b>설계 근거:</b>
 * <ul>
 *   <li><b>주기적 복구:</b> 콜백이 오지 않아도 자동으로 상태 복구</li>
 *   <li><b>Eventually Consistent:</b> 약간의 지연 허용 가능</li>
 *   <li><b>안전한 처리:</b> 각 주문별로 독립적으로 처리하여 실패 시에도 다른 주문에 영향 없음</li>
 *   <li><b>성능 고려:</b> 배치로 처리하여 PG 시스템 부하 최소화</li>
 * </ul>
 * </p>
 * <p>
 * <b>레이어 위치 근거:</b>
 * <ul>
 *   <li>스케줄링은 기술적 관심사이므로 Infrastructure Layer에 위치</li>
 *   <li>비즈니스 로직은 Application Layer의 PurchasingFacade에 위임</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentRecoveryScheduler {

    private final OrderRepository orderRepository;
    private final UserJpaRepository userJpaRepository;
    private final PurchasingFacade purchasingFacade;

    /**
     * PENDING 상태인 주문들의 결제 상태를 복구합니다.
     * <p>
     * 1분마다 실행되어 PENDING 상태인 주문들을 조회하고,
     * 각 주문에 대해 PG 결제 상태 확인 API를 호출하여 상태를 복구합니다.
     * </p>
     * <p>
     * <b>처리 전략:</b>
     * <ul>
     *   <li><b>배치 처리:</b> 한 번에 여러 주문 처리</li>
     *   <li><b>독립적 처리:</b> 각 주문별로 독립적으로 처리하여 실패 시에도 다른 주문에 영향 없음</li>
     *   <li><b>안전한 예외 처리:</b> 개별 주문 처리 실패 시에도 계속 진행</li>
     * </ul>
     * </p>
     */
    @Scheduled(fixedDelay = 60000) // 1분마다 실행
    public void recoverPendingOrders() {
        try {
            log.debug("결제 상태 복구 스케줄러 시작");

            // PENDING 상태인 주문들 조회
            List<Order> pendingOrders = orderRepository.findAllByStatus(OrderStatus.PENDING);

            if (pendingOrders.isEmpty()) {
                log.debug("복구할 PENDING 상태 주문이 없습니다.");
                return;
            }

            log.info("PENDING 상태 주문 {}건에 대한 결제 상태 복구 시작", pendingOrders.size());

            int successCount = 0;
            int failureCount = 0;

            // 각 주문에 대해 결제 상태 확인 및 복구
            for (Order order : pendingOrders) {
                try {
                    // Order의 userId는 User의 id (Long)이므로 User를 조회하여 userId (String)를 가져옴
                    var userOptional = userJpaRepository.findById(order.getUserId());
                    if (userOptional.isEmpty()) {
                        log.warn("주문의 사용자를 찾을 수 없습니다. 복구를 건너뜁니다. (orderId: {}, userId: {})",
                            order.getId(), order.getUserId());
                        failureCount++;
                        continue;
                    }

                    String userId = userOptional.get().getUserId();
                    
                    // 결제 상태 확인 및 복구
                    purchasingFacade.recoverOrderStatusByPaymentCheck(userId, order.getId());
                    successCount++;
                } catch (Exception e) {
                    // 개별 주문 처리 실패 시에도 계속 진행
                    log.error("주문 상태 복구 중 오류 발생. (orderId: {})", order.getId(), e);
                    failureCount++;
                }
            }

            log.info("결제 상태 복구 완료. 성공: {}건, 실패: {}건", successCount, failureCount);

        } catch (Exception e) {
            log.error("결제 상태 복구 스케줄러 실행 중 오류 발생", e);
        }
    }
}

