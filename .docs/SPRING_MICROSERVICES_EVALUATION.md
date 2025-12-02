# 『스프링으로 하는 마이크로서비스 구축 2e』 기준 프로젝트 평가

## 평가 개요

이 문서는 **『스프링으로 하는 마이크로서비스 구축 2e』**의 핵심 챕터(1장, 8장, 13장, 20장)를 기준으로 현재 프로젝트의 PG 장애 대응 구현을 평가합니다.

---

## 📘 챕터별 평가

### 📍 1장 – 마이크로서비스 소개 (p56)

#### 책의 핵심 원칙

**"외부 서비스 장애는 빠르게 스레드 풀을 고갈시켜 전체 마이크로서비스 장애로 전파됨"**

**해결책: Circuit Breaker**
- 문제 감지 → 즉시 실패(fast-fail) → 회복 테스트 → 다시 close
- Half-open 상태로 전환하여 회복 여부 판단

#### 현재 프로젝트 평가

**✅ 완벽하게 구현됨**

**1. Fast-Fail 패턴 구현**
```java
// PurchasingFacade.requestPaymentToGateway()
// 유저 요청 경로: Retry 없음 (maxAttempts: 1)
// 실패 시 즉시 PENDING 상태로 응답하여 스레드 풀 고갈 방지
```

**2. Circuit Breaker 동작 원리 구현**
```yaml
# application.yml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 20
        minimumNumberOfCalls: 5
        failureRateThreshold: 50 # 50% 이상 실패 시 Open
        waitDurationInOpenState: 10s # 10초 후 Half-Open으로 전환
        automaticTransitionFromOpenToHalfOpenEnabled: true
        permittedNumberOfCallsInHalfOpenState: 3
```

**3. PENDING 상태 + Fallback 패턴**
```java
// PaymentGatewayClientFallback.java
// Circuit Breaker OPEN 시 Fallback 호출
// 주문은 PENDING 상태로 유지되어 callback/조회로 최종 상태 보정
```

**평가 점수**: ⭐⭐⭐⭐⭐ (5/5)
- ✅ Fast-fail 패턴 완벽 구현
- ✅ Circuit Breaker 3 상태 (Closed → Open → Half-Open) 자동 전환
- ✅ PENDING 상태로 응답하여 스레드 풀 보호

---

### 📍 8장 – 스프링 클라우드 소개 (p293~307)

#### 책의 핵심 원칙

**"스프링 클라우드 환경에서 복원력(resilience)을 강화하기 위한 기술 스택"**

**Resilience4j 기반 구성:**
- Timeout
- Retry
- CircuitBreaker
- Fallback 전략

#### 현재 프로젝트 평가

**✅ 완벽하게 구현됨**

**1. Resilience4j 통합**
```yaml
# application.yml
feign:
  circuitbreaker:
    enabled: true
  resilience4j:
    enabled: true
```

**2. FeignClient에 Resilience 패턴 적용**
```java
@FeignClient(
    name = "paymentGatewayClient",
    url = "${payment-gateway.url}",
    path = "/api/v1/payments",
    fallback = PaymentGatewayClientFallback.class // Fallback 지정
)
public interface PaymentGatewayClient {
    // Circuit Breaker, Bulkhead 자동 적용
}
```

**3. 주문 마이크로서비스 단에서 구현**
- ✅ Fallback 전략: `PaymentGatewayClientFallback`
- ✅ Timeout: Feign timeout + TimeLimiter
- ✅ Retry: Exponential Backoff (스케줄러 경로)
- ✅ Circuit Breaker: Resilience4j

**평가 점수**: ⭐⭐⭐⭐⭐ (5/5)
- ✅ Resilience4j 완벽 통합
- ✅ 모든 Resilience 패턴 적용
- ✅ 주문 마이크로서비스 단에서 독립적으로 구현

---

### 📍 13장 – Resilience4j를 사용한 복원력 개선 (p428~446)

#### 책의 핵심 원칙

**"Retry, Circuit Breaker, TimeLimiter를 Spring Boot에서 어떻게 구성하는지 코드 중심으로 설명"**

**과제 적용 포인트:**
- `@Retry`, `@CircuitBreaker`, `@TimeLimiter` 사용
- `fallbackMethod` 사용 예제
- 최대 시도 횟수/대기 시간(backoff) 설계

#### 현재 프로젝트 평가

**✅ 완벽하게 구현됨**

**1. Retry 설정 (Exponential Backoff)**

**YAML 설정:**
```yaml
resilience4j:
  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 500ms
        retryExceptions:
          - feign.FeignException$InternalServerError
          - feign.FeignException$ServiceUnavailable
          - feign.FeignException$GatewayTimeout
          - java.net.SocketTimeoutException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - feign.FeignException$BadRequest
          - feign.FeignException$Unauthorized
          - feign.FeignException$Forbidden
          - feign.FeignException$NotFound
```

**Java Config (Exponential Backoff):**
```java
// Resilience4jRetryConfig.java
IntervalFunction intervalFunction = IntervalFunction
    .ofExponentialRandomBackoff(
        Duration.ofMillis(500),  // 초기 대기 시간
        2.0,                      // 배수 (exponential multiplier)
        Duration.ofSeconds(5)     // 최대 대기 시간
    );

RetryConfig retryConfig = RetryConfig.custom()
    .maxAttempts(3)
    .intervalFunction(intervalFunction)  // Exponential Backoff 적용
    .retryOnException(...)
    .ignoreExceptions(...)
    .build();
```

**재시도 시퀀스:**
- 1차 시도: 즉시 실행
- 2차 시도: 500ms 후 (500ms * 2^0)
- 3차 시도: 1000ms 후 (500ms * 2^1)

**2. Circuit Breaker 설정**

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 20 # 과제 권장값
        minimumNumberOfCalls: 5
        failureRateThreshold: 50 # 50% 이상 실패 시 Open
        slowCallRateThreshold: 50 # 50% 이상 느리면 Open
        slowCallDurationThreshold: 2s # 2초 이상 느리면 느린 호출로 간주
        waitDurationInOpenState: 10s # 10초 후 Half-Open으로 전환
        automaticTransitionFromOpenToHalfOpenEnabled: true
        permittedNumberOfCallsInHalfOpenState: 3
```

**3. TimeLimiter 설정**

```yaml
resilience4j:
  timelimiter:
    configs:
      default:
        timeoutDuration: 6s # Feign readTimeout과 동일
        cancelRunningFuture: true
```

**4. Fallback 구현**

```java
@Component
public class PaymentGatewayClientFallback implements PaymentGatewayClient {
    @Override
    public ApiResponse<TransactionResponse> requestPayment(...) {
        // Circuit Breaker OPEN 시 즉시 Fallback 응답 반환
        return new ApiResponse<>(
            new Metadata(Result.FAIL, "CIRCUIT_BREAKER_OPEN", "..."),
            null
        );
    }
}
```

**5. 유저 요청 경로 vs 스케줄러 경로 분리**

**유저 요청 경로 (`paymentGatewayClient`):**
- Retry 없음 (`maxAttempts: 1`)
- 빠른 실패로 스레드 점유 최소화
- 주문은 PENDING 상태로 유지

**스케줄러 경로 (`paymentGatewaySchedulerClient`):**
- Retry 적용 (`maxAttempts: 3`)
- Exponential Backoff 적용
- 비동기/배치 기반이므로 안전하게 Retry 가능

**평가 점수**: ⭐⭐⭐⭐⭐ (5/5)
- ✅ Retry 설정 완벽 (Exponential Backoff + Jitter)
- ✅ Circuit Breaker 설정 완벽 (과제 권장값 준수)
- ✅ TimeLimiter 설정 완벽
- ✅ Fallback 구현 완벽
- ✅ 유저 요청 경로와 스케줄러 경로 분리 (실무 권장 패턴)

---

### 📍 20장 – 마이크로서비스 모니터링 (p726~730)

#### 책의 핵심 원칙

**"재시도 메커니즘과 서킷 브레이커의 metrics(health 상태)를 Grafana에서 확인하는 실습 제공"**

**과제 평가 시 시각화 항목:**
- Circuit Breaker 상태 (Open/Closed/Half-open)
- Retry count
- Timeout 발생 빈도
- PG 실패/성공 비율
- Fallback 발생 횟수

#### 현재 프로젝트 평가

**✅ 부분적으로 구현됨**

**1. Resilience4j 기본 메트릭 노출**

```yaml
# monitoring.yml
management:
  endpoints:
    web:
      exposure:
        include:
          - health
          - prometheus
          - metrics
          - circuitbreakers # Resilience4j Circuit Breaker 메트릭 노출
          - bulkheads # Resilience4j Bulkhead 메트릭 노출
  metrics:
    export:
      prometheus:
        enabled: true
```

**노출되는 메트릭:**
- ✅ `resilience4j_circuitbreaker_state`: Circuit Breaker 상태 (0: CLOSED, 1: OPEN, 2: HALF_OPEN)
- ✅ `resilience4j_circuitbreaker_calls_total`: Circuit Breaker 호출 수 (successful, failed, not_permitted)
- ✅ `resilience4j_circuitbreaker_failure_rate`: 실패율
- ✅ `resilience4j_circuitbreaker_slow_calls_total`: 느린 호출 수
- ✅ `resilience4j_circuitbreaker_not_permitted_calls_total`: Circuit Open 상태에서 차단된 호출 수
- ✅ `resilience4j_bulkhead_available_concurrent_calls`: 사용 가능한 동시 호출 수
- ✅ `resilience4j_retry_calls_total`: Retry 호출 수 (successful_without_retry, successful_with_retry, failed_with_retry, failed_without_retry)

**2. Grafana 대시보드 구현**

**파일**: `docker/grafana/dashboards/resilience4j-circuit-breaker.json`

**대시보드 패널:**
- ✅ Circuit Breaker State (CLOSED/OPEN/HALF_OPEN)
- ✅ Circuit Breaker Calls (successful, failed, not_permitted)
- ✅ Circuit Breaker Failure Rate
- ✅ Bulkhead Available Concurrent Calls
- ✅ Bulkhead Thread Pool Usage
- ✅ Retry Attempts

**3. Prometheus 연동**

```yaml
# docker/grafana/prometheus.yml
scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8081']
```

**4. 커스텀 메트릭**

**⚠️ 현재 미구현** (사용자가 삭제함)

**미구현 항목:**
- ❌ Timeout 발생 횟수 메트릭 (`payment.gateway.timeout`)
- ❌ Fallback 호출 횟수 메트릭 (`payment.gateway.fallback`)
- ❌ PG 콜백 수신 횟수 메트릭 (`payment.callback.received`)
- ❌ 주문 상태 전이 메트릭 (`order.status.transition`)
- ❌ PG 요청 처리 시간 메트릭 (`payment.gateway.request.duration`)

**하지만 기본 메트릭으로 대체 가능:**
- ✅ Timeout: `resilience4j_circuitbreaker_slow_calls_total` (slowCallDurationThreshold: 2s)
- ✅ Fallback: `resilience4j_circuitbreaker_not_permitted_calls_total` (Circuit Open 시)
- ✅ Retry: `resilience4j_retry_calls_total` (Retry 시도 횟수)

**평가 점수**: ⭐⭐⭐⭐ (4/5)
- ✅ Resilience4j 기본 메트릭 완벽 노출
- ✅ Prometheus 연동 완료
- ✅ Grafana 대시보드 구현 완료
- ⚠️ 커스텀 메트릭 미구현 (하지만 기본 메트릭으로 대체 가능)

---

## 📊 종합 평가

### 전체 점수

| 챕터 | 평가 항목 | 점수 | 비고 |
|------|----------|------|------|
| 1장 | Fast-Fail 패턴, Circuit Breaker 동작 원리 | ⭐⭐⭐⭐⭐ (5/5) | 완벽 구현 |
| 8장 | Resilience4j 통합, Resilience 패턴 적용 | ⭐⭐⭐⭐⭐ (5/5) | 완벽 구현 |
| 13장 | Retry, Circuit Breaker, TimeLimiter, Fallback | ⭐⭐⭐⭐⭐ (5/5) | 완벽 구현 |
| 20장 | 메트릭 노출, Grafana 대시보드 | ⭐⭐⭐⭐ (4/5) | 기본 메트릭 완벽, 커스텀 메트릭 미구현 |

**종합 점수**: ⭐⭐⭐⭐⭐ (4.75/5.0) = **95%**

---

## ✅ 책 기준 완벽 구현 항목

### 1. Circuit Breaker (1장, 13장)

**✅ 완벽 구현**
- ✅ 3 상태 자동 전환 (Closed → Open → Half-Open → Closed)
- ✅ 실패율 기반 Open 전환 (50% 임계값)
- ✅ 느린 호출 감지 (2초 이상)
- ✅ Half-Open 상태에서 회복 테스트 (3회 호출)
- ✅ Fast-fail 패턴 (PENDING 상태로 응답)

### 2. Retry (13장)

**✅ 완벽 구현**
- ✅ Exponential Backoff 적용 (500ms → 1000ms)
- ✅ Jitter 적용 (thundering herd 문제 방지)
- ✅ 재시도 예외 구분 (5xx만 재시도, 4xx는 무시)
- ✅ 유저 요청 경로: Retry 없음 (빠른 실패)
- ✅ 스케줄러 경로: Retry 적용 (비동기/배치 기반)

### 3. Timeout (13장)

**✅ 완벽 구현**
- ✅ Feign 연결 타임아웃 (2초)
- ✅ Feign 읽기 타임아웃 (6초)
- ✅ TimeLimiter 타임아웃 (6초)
- ✅ 타임아웃 발생 시 즉시 상태 확인 API 호출

### 4. Fallback (13장)

**✅ 완벽 구현**
- ✅ `PaymentGatewayClientFallback` 구현
- ✅ Circuit Breaker OPEN 시 즉시 Fallback 호출
- ✅ PENDING 상태로 응답하여 나중에 복구 가능

### 5. 메트릭 & 모니터링 (20장)

**✅ 기본 메트릭 완벽 구현**
- ✅ Resilience4j 기본 메트릭 노출
- ✅ Prometheus 연동
- ✅ Grafana 대시보드 구현
- ✅ Circuit Breaker 상태 시각화
- ✅ Retry 시도 횟수 시각화
- ✅ Bulkhead 사용률 시각화

**⚠️ 커스텀 메트릭 미구현**
- ❌ Timeout/Fallback 발생 횟수 메트릭 (하지만 기본 메트릭으로 대체 가능)

---

## 🎯 책 기준 과제 완성도

### 과제 요구사항 대비 구현 상태

| 요구사항 | 책 기준 | 현재 구현 | 평가 |
|---------|---------|----------|------|
| Timeout | Feign timeout + TimeLimiter | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |
| Retry | Exponential Backoff | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |
| Circuit Breaker | 실패율 기반 Open 전환 | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |
| Fallback | PENDING 상태로 응답 | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |
| 메트릭 관찰 | Grafana 대시보드 | ✅ 기본 메트릭 완벽 | ⭐⭐⭐⭐ |

**과제 완성도**: **95%** (거의 완벽)

---

## 💡 개선 권장 사항

### 1. 커스텀 메트릭 추가 (선택적)

**현재 상태**: 기본 메트릭으로 대체 가능하지만, 비즈니스 로직 레벨의 메트릭이 더 명확함

**권장 구현:**
- Timeout 발생 횟수 메트릭
- Fallback 호출 횟수 메트릭
- PG 콜백 수신 횟수 메트릭
- 주문 상태 전이 메트릭

**하지만**: 기본 메트릭만으로도 충분히 모니터링 가능하므로 **선택적** 개선 사항

---

## 📝 결론

### 책 기준 평가 요약

**현재 프로젝트는 『스프링으로 하는 마이크로서비스 구축 2e』의 핵심 원칙을 거의 완벽하게 반영하고 있습니다:**

1. ✅ **1장 (Circuit Breaker)**: Fast-fail 패턴, 3 상태 자동 전환 완벽 구현
2. ✅ **8장 (Resilience4j)**: Resilience4j 통합 및 모든 Resilience 패턴 적용 완벽
3. ✅ **13장 (Retry/CB/Timeout)**: Exponential Backoff, Circuit Breaker, TimeLimiter, Fallback 완벽 구현
4. ✅ **20장 (모니터링)**: 기본 메트릭 노출, Grafana 대시보드 완벽 구현

**종합 평가**: ⭐⭐⭐⭐⭐ (4.75/5.0) = **95%**

**과제 완성도**: **거의 완벽** - 책의 핵심 원칙을 실무 권장 패턴과 함께 잘 반영하고 있습니다.

---

## 참고 자료

- [Resilience4j 공식 문서](https://resilience4j.readme.io/)
- [Spring Cloud OpenFeign 문서](https://spring.io/projects/spring-cloud-openfeign)
- [Micrometer 메트릭 문서](https://micrometer.io/docs)

