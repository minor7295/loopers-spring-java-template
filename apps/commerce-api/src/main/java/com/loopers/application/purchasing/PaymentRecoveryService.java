package com.loopers.application.purchasing;

import com.loopers.domain.order.OrderStatusUpdater;
import com.loopers.infrastructure.paymentgateway.DelayProvider;
import com.loopers.infrastructure.paymentgateway.PaymentGatewayAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 결제 복구 서비스.
 * <p>
 * 타임아웃 발생 후 결제 상태를 확인하여 주문 상태를 복구합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryService {
    
    private final PaymentGatewayAdapter paymentGatewayAdapter;
    private final OrderStatusUpdater orderStatusUpdater;
    private final DelayProvider delayProvider;
    
    /**
     * 타임아웃 발생 후 결제 상태를 확인하여 주문 상태를 복구합니다.
     * <p>
     * 타임아웃은 요청이 전송되었을 수 있으므로, 실제 결제 상태를 확인하여
     * 결제가 성공했다면 주문을 완료하고, 실패했다면 주문을 취소합니다.
     * </p>
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     */
    public void recoverAfterTimeout(String userId, Long orderId) {
        try {
            // 잠시 대기 후 상태 확인 (PG 처리 시간 고려)
            // 타임아웃이 발생했지만 요청은 전송되었을 수 있으므로,
            // PG 시스템이 처리할 시간을 주기 위해 짧은 대기
            delayProvider.delay(Duration.ofSeconds(1));
            
            // PG에서 주문별 결제 정보 조회
            var status = paymentGatewayAdapter.getPaymentStatus(userId, String.valueOf(orderId));
            
            // 별도 트랜잭션으로 상태 업데이트
            orderStatusUpdater.updateByPaymentStatus(orderId, status, null, null);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("타임아웃 후 상태 확인 중 인터럽트 발생. (orderId: {})", orderId);
        } catch (Exception e) {
            // 기타 오류: 나중에 스케줄러로 복구 가능
            log.error("타임아웃 후 상태 확인 중 오류 발생. 나중에 스케줄러로 복구됩니다. (orderId: {})", orderId, e);
        }
    }
}

