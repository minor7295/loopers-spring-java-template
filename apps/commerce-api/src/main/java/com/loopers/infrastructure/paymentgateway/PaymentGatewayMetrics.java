package com.loopers.infrastructure.paymentgateway;

import io.micrometer.core.instrument.Counter;
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
        Counter.builder("payment.gateway.server.error")
            .description("PG 서버 오류 발생 횟수 (5xx)")
            .tag("client", clientName)
            .tag("status", String.valueOf(status))
            .register(meterRegistry)
            .increment();
    }
    
    /**
     * PG 타임아웃 발생 횟수를 기록합니다.
     *
     * @param clientName 클라이언트 이름
     */
    public void recordTimeout(String clientName) {
        Counter.builder("payment.gateway.timeout")
            .description("PG 결제 요청 타임아웃 발생 횟수")
            .tag("client", clientName)
            .register(meterRegistry)
            .increment();
    }
    
    /**
     * PG 클라이언트 오류(4xx) 발생 횟수를 기록합니다.
     *
     * @param clientName 클라이언트 이름
     * @param status HTTP 상태 코드
     */
    public void recordClientError(String clientName, int status) {
        Counter.builder("payment.gateway.client.error")
            .description("PG 클라이언트 오류 발생 횟수 (4xx)")
            .tag("client", clientName)
            .tag("status", String.valueOf(status))
            .register(meterRegistry)
            .increment();
    }
    
    /**
     * Fallback 호출 횟수를 기록합니다.
     *
     * @param clientName 클라이언트 이름
     */
    public void recordFallback(String clientName) {
        Counter.builder("payment.gateway.fallback")
            .description("PG 결제 요청 Fallback 호출 횟수")
            .tag("client", clientName)
            .register(meterRegistry)
            .increment();
    }
    
    /**
     * PG 결제 요청 성공 횟수를 기록합니다.
     *
     * @param clientName 클라이언트 이름
     */
    public void recordSuccess(String clientName) {
        Counter.builder("payment.gateway.request.success")
            .description("PG 결제 요청 성공 횟수")
            .tag("client", clientName)
            .register(meterRegistry)
            .increment();
    }
}

