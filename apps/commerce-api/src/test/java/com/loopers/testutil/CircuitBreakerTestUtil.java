package com.loopers.testutil;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Circuit Breaker 테스트 유틸리티.
 * <p>
 * Circuit Breaker를 특정 상태로 만들거나, 실패를 유발하여 Circuit Breaker를 열리게 하는 유틸리티 메서드를 제공합니다.
 * </p>
 */
@Component
public class CircuitBreakerTestUtil {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTestUtil.class);
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    public CircuitBreakerTestUtil(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Circuit Breaker를 OPEN 상태로 전환합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름 (예: "paymentGatewayClient")
     */
    public void openCircuitBreaker(String circuitBreakerName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName);
        if (circuitBreaker != null) {
            circuitBreaker.transitionToOpenState();
            log.info("Circuit Breaker '{}'를 OPEN 상태로 전환했습니다.", circuitBreakerName);
        } else {
            log.warn("Circuit Breaker '{}'를 찾을 수 없습니다.", circuitBreakerName);
        }
    }

    /**
     * Circuit Breaker를 HALF_OPEN 상태로 전환합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     */
    public void halfOpenCircuitBreaker(String circuitBreakerName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName);
        if (circuitBreaker != null) {
            circuitBreaker.transitionToHalfOpenState();
            log.info("Circuit Breaker '{}'를 HALF_OPEN 상태로 전환했습니다.", circuitBreakerName);
        } else {
            log.warn("Circuit Breaker '{}'를 찾을 수 없습니다.", circuitBreakerName);
        }
    }

    /**
     * Circuit Breaker를 CLOSED 상태로 리셋합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     */
    public void resetCircuitBreaker(String circuitBreakerName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName);
        if (circuitBreaker != null) {
            circuitBreaker.reset();
            log.info("Circuit Breaker '{}'를 리셋했습니다.", circuitBreakerName);
        } else {
            log.warn("Circuit Breaker '{}'를 찾을 수 없습니다.", circuitBreakerName);
        }
    }

    /**
     * Circuit Breaker의 현재 상태를 반환합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     * @return Circuit Breaker 상태 (CLOSED, OPEN, HALF_OPEN)
     */
    public CircuitBreaker.State getCircuitBreakerState(String circuitBreakerName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName);
        if (circuitBreaker != null) {
            return circuitBreaker.getState();
        }
        return null;
    }

    /**
     * 실패를 유발하여 Circuit Breaker를 OPEN 상태로 만듭니다.
     * <p>
     * 이 메서드는 실패 임계값을 초과하도록 여러 번 실패를 유발합니다.
     * </p>
     *
     * @param circuitBreakerName Circuit Breaker 이름
     * @param failureFunction 실패를 유발하는 함수 (예: PG API 호출)
     * @param minFailures 최소 실패 횟수 (실패율 임계값을 초과하기 위해 필요한 실패 횟수)
     */
    public void triggerCircuitBreakerOpen(String circuitBreakerName, Runnable failureFunction, int minFailures) {
        log.info("Circuit Breaker '{}'를 OPEN 상태로 만들기 위해 {}번의 실패를 유발합니다.", circuitBreakerName, minFailures);
        
        // Circuit Breaker 리셋
        resetCircuitBreaker(circuitBreakerName);
        
        // 실패 유발
        AtomicInteger failureCount = new AtomicInteger(0);
        for (int i = 0; i < minFailures; i++) {
            try {
                failureFunction.run();
            } catch (Exception e) {
                failureCount.incrementAndGet();
                log.debug("실패 {}번 발생: {}", failureCount.get(), e.getMessage());
            }
        }
        
        log.info("총 {}번의 실패를 유발했습니다. Circuit Breaker 상태: {}", 
            failureCount.get(), getCircuitBreakerState(circuitBreakerName));
    }

    /**
     * Circuit Breaker가 OPEN 상태인지 확인합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     * @return OPEN 상태이면 true
     */
    public boolean isCircuitBreakerOpen(String circuitBreakerName) {
        CircuitBreaker.State state = getCircuitBreakerState(circuitBreakerName);
        return state == CircuitBreaker.State.OPEN;
    }

    /**
     * Circuit Breaker가 HALF_OPEN 상태인지 확인합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     * @return HALF_OPEN 상태이면 true
     */
    public boolean isCircuitBreakerHalfOpen(String circuitBreakerName) {
        CircuitBreaker.State state = getCircuitBreakerState(circuitBreakerName);
        return state == CircuitBreaker.State.HALF_OPEN;
    }

    /**
     * Circuit Breaker가 CLOSED 상태인지 확인합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     * @return CLOSED 상태이면 true
     */
    public boolean isCircuitBreakerClosed(String circuitBreakerName) {
        CircuitBreaker.State state = getCircuitBreakerState(circuitBreakerName);
        return state == CircuitBreaker.State.CLOSED;
    }
}

