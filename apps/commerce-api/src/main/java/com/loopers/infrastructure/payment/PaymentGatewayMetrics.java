package com.loopers.infrastructure.payment;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제 게이트웨이 메트릭.
 * <p>
 * PG 서버 오류, 타임아웃, Fallback 등의 이벤트를 Prometheus 메트릭으로 기록합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class PaymentGatewayMetrics {
    
    private final MeterRegistry meterRegistry;
    
    /**
     * PG 서버 오류(5xx) 발생 횟수를 기록합니다.
     *
     * @param clientName 클라이언트 이름 (paymentGatewayClient, paymentGatewaySchedulerClient)
     * @param status HTTP 상태 코드
     */
    public void recordServerError(String clientName, int status) {
        meterRegistry.counter(
            "payment.gateway.server.error",
            "client", clientName,
            "status", String.valueOf(status)
        ).increment();
    }
    
    /**
     * PG 타임아웃 발생 횟수를 기록합니다.
     *
     * @param clientName 클라이언트 이름
     */
    public void recordTimeout(String clientName) {
        meterRegistry.counter(
            "payment.gateway.timeout",
            "client", clientName
        ).increment();
    }
    
    /**
     * PG 클라이언트 오류(4xx) 발생 횟수를 기록합니다.
     *
     * @param clientName 클라이언트 이름
     * @param status HTTP 상태 코드
     */
    public void recordClientError(String clientName, int status) {
        meterRegistry.counter(
            "payment.gateway.client.error",
            "client", clientName,
            "status", String.valueOf(status)
        ).increment();
    }
    
    /**
     * Fallback 호출 횟수를 기록합니다.
     *
     * @param clientName 클라이언트 이름
     */
    public void recordFallback(String clientName) {
        meterRegistry.counter(
            "payment.gateway.fallback",
            "client", clientName
        ).increment();
    }
    
    /**
     * PG 결제 요청 성공 횟수를 기록합니다.
     *
     * @param clientName 클라이언트 이름
     */
    public void recordSuccess(String clientName) {
        meterRegistry.counter(
            "payment.gateway.request.success",
            "client", clientName
        ).increment();
    }
}
