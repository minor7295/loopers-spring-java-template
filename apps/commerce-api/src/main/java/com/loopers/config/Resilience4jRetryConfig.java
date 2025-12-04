package com.loopers.config;

import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.core.IntervalFunction;
import lombok.extern.slf4j.Slf4j;
import feign.FeignException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.net.SocketTimeoutException;

/**
 * Resilience4j Retry 설정 커스터마이징.
 * <p>
 * 실무 권장 패턴에 따라 메서드별로 다른 Retry 정책을 적용합니다:
 * </p>
 * <p>
 * <b>Retry 정책:</b>
 * <ul>
 *   <li><b>결제 요청 API (requestPayment):</b> Retry 없음 (유저 요청 경로 - 빠른 실패)</li>
 *   <li><b>조회 API (getTransactionsByOrder, getTransaction):</b> Exponential Backoff 적용 (스케줄러 - 안전)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Exponential Backoff 전략 (조회 API용):</b>
 * <ul>
 *   <li><b>초기 대기 시간:</b> 500ms</li>
 *   <li><b>배수(multiplier):</b> 2 (각 재시도마다 2배씩 증가)</li>
 *   <li><b>최대 대기 시간:</b> 5초 (너무 길어지지 않도록 제한)</li>
 *   <li><b>랜덤 jitter:</b> 활성화 (thundering herd 문제 방지)</li>
 * </ul>
 * </p>
 * <p>
 * <b>재시도 시퀀스 예시 (조회 API):</b>
 * <ol>
 *   <li>1차 시도: 즉시 실행</li>
 *   <li>2차 시도: 500ms 후 (500ms * 2^0)</li>
 *   <li>3차 시도: 1000ms 후 (500ms * 2^1)</li>
 * </ol>
 * </p>
 * <p>
 * <b>설계 근거:</b>
 * <ul>
 *   <li><b>유저 요청 경로:</b> 긴 Retry는 스레드 점유 비용이 크므로 Retry 없이 빠르게 실패</li>
 *   <li><b>스케줄러 경로:</b> 비동기/배치 기반이므로 Retry가 안전하게 적용 가능 (Nice-to-Have 요구사항 충족)</li>
 *   <li><b>Eventually Consistent:</b> 실패 시 주문은 PENDING 상태로 유지되어 스케줄러에서 복구</li>
 *   <li><b>일시적 오류 복구:</b> 네트워크 일시적 오류나 PG 서버 일시적 장애 시 자동 복구</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 2.0
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
     * @return RetryRegistry (커스터마이징된 설정이 적용됨)
     */
    @Bean
    public RetryRegistry retryRegistry() {
        RetryRegistry retryRegistry = io.github.resilience4j.retry.RetryRegistry.ofDefaults();
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
                if (throwable instanceof FeignException feignException) {
                    int status = feignException.status();
                    // 5xx 서버 오류만 재시도
                    if (status >= 500 && status < 600) {
                        log.debug("재시도 대상 예외: FeignException (status: {})", status);
                        return true;
                    }
                    return false;
                }
                if (throwable instanceof SocketTimeoutException ||
                    throwable instanceof TimeoutException) {
                    log.debug("재시도 대상 예외: {}", throwable.getClass().getSimpleName());
                    return true;
                }
                return false;
            })
            // ignoreExceptions는 사용하지 않음
            // retryOnException에서 5xx만 재시도하고 4xx는 제외하므로,
            // 별도로 ignoreExceptions를 설정할 필요가 없음
            .build();

        // 결제 요청 API: 유저 요청 경로에서 사용되므로 Retry 비활성화 (빠른 실패)
        // 실패 시 주문은 PENDING 상태로 유지되어 스케줄러에서 복구됨
        RetryConfig noRetryConfig = RetryConfig.custom()
            .maxAttempts(1)  // 재시도 없음 (초기 시도만)
            .build();
        retryRegistry.addConfiguration("paymentGatewayClient", noRetryConfig);

        // 스케줄러 전용 클라이언트: 비동기/배치 기반으로 Retry 적용
        // Exponential Backoff 적용하여 일시적 오류 자동 복구
        retryRegistry.addConfiguration("paymentGatewaySchedulerClient", retryConfig);

        log.info("Resilience4j Retry 설정 완료:");
        log.info("  - 결제 요청 API (paymentGatewayClient): Retry 없음 (유저 요청 경로 - 빠른 실패)");
        log.info("  - 조회 API (paymentGatewaySchedulerClient): Exponential Backoff 적용 (스케줄러 - 비동기/배치 기반 Retry)");

        return retryRegistry;
    }
}

