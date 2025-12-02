package com.loopers.config;

import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.IntervalFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FeignException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.net.SocketTimeoutException;

/**
 * Resilience4j Retry 설정 커스터마이징.
 * <p>
 * Exponential Backoff 전략을 적용하여 재시도 간격을 점진적으로 증가시킵니다.
 * </p>
 * <p>
 * <b>Exponential Backoff 전략:</b>
 * <ul>
 *   <li><b>초기 대기 시간:</b> 500ms</li>
 *   <li><b>배수(multiplier):</b> 2 (각 재시도마다 2배씩 증가)</li>
 *   <li><b>최대 대기 시간:</b> 5초 (너무 길어지지 않도록 제한)</li>
 *   <li><b>랜덤 jitter:</b> 활성화 (thundering herd 문제 방지)</li>
 * </ul>
 * </p>
 * <p>
 * <b>재시도 시퀀스 예시:</b>
 * <ol>
 *   <li>1차 시도: 즉시 실행</li>
 *   <li>2차 시도: 500ms 후 (500ms * 2^0)</li>
 *   <li>3차 시도: 1000ms 후 (500ms * 2^1)</li>
 * </ol>
 * </p>
 * <p>
 * <b>장점:</b>
 * <ul>
 *   <li>서버 부하 감소: 재시도 간격이 점진적으로 증가하여 서버 부하 분산</li>
 *   <li>Thundering herd 방지: 랜덤 jitter로 동시 재시도 방지</li>
 *   <li>일시적 오류 복구 시간 확보: 서버가 복구될 시간을 제공</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Configuration
public class Resilience4jRetryConfig {

    /**
     * PaymentGatewayClient용 Retry 설정을 커스터마이징합니다.
     * <p>
     * Exponential Backoff 전략을 적용하여 재시도 간격을 점진적으로 증가시킵니다.
     * </p>
     *
     * @param retryRegistry Resilience4j RetryRegistry
     * @return RetryRegistry (커스터마이징된 설정이 적용됨)
     */
    @Bean
    public RetryRegistry retryRegistry(RetryRegistry retryRegistry) {
        // Exponential Backoff 설정
        // - 초기 대기 시간: 500ms
        // - 배수: 2 (각 재시도마다 2배씩 증가)
        // - 최대 대기 시간: 5초
        // - 랜덤 jitter: 활성화 (thundering herd 문제 방지)
        IntervalFunction intervalFunction = IntervalFunction
            .ofExponentialRandomBackoff(
                Duration.ofMillis(500),  // 초기 대기 시간
                2.0,                      // 배수 (exponential multiplier)
                Duration.ofSeconds(5)     // 최대 대기 시간
            );

        // RetryConfig 커스터마이징
        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(3)  // 최대 재시도 횟수 (초기 시도 포함)
            .intervalFunction(intervalFunction)  // Exponential Backoff 적용
            .retryOnException(throwable -> {
                // 일시적 오류만 재시도: 5xx 서버 오류, 타임아웃, 네트워크 오류
                if (throwable instanceof FeignException.InternalServerError ||
                    throwable instanceof FeignException.ServiceUnavailable ||
                    throwable instanceof FeignException.GatewayTimeout ||
                    throwable instanceof SocketTimeoutException ||
                    throwable instanceof TimeoutException) {
                    log.debug("재시도 대상 예외: {}", throwable.getClass().getSimpleName());
                    return true;
                }
                return false;
            })
            .ignoreExceptions(
                // 클라이언트 오류(4xx)는 재시도하지 않음: 비즈니스 로직 오류이므로 재시도해도 성공하지 않음
                FeignException.BadRequest.class,
                FeignException.Unauthorized.class,
                FeignException.Forbidden.class,
                FeignException.NotFound.class
            )
            .build();

        // paymentGatewayClient 인스턴스에 커스터마이징된 설정 적용
        retryRegistry.addConfiguration("paymentGatewayClient", retryConfig);

        log.info("Resilience4j Retry 설정 완료: Exponential Backoff 적용 (초기: 500ms, 배수: 2, 최대: 5초)");

        return retryRegistry;
    }
}

