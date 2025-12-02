# Health Checks & Observability 개선 사항

## Chapter 7 — Health Checks & Probing 개선 사항

### 현재 상태
- ✅ Liveness/Readiness Probe 활성화됨 (`monitoring.yml`)
- ✅ Circuit Breaker Health Indicator 활성화됨
- ⚠️ **PG 서비스 상태를 Readiness Probe에 포함하는 커스텀 Health Indicator 없음**

### 개선 필요 사항

#### 1. PG 서비스 Health Indicator 구현 (높은 우선순위)

**문제점**:
- 현재 Readiness Probe는 기본적인 애플리케이션 상태만 확인
- PG 서비스가 실제로 사용 가능한지 확인하지 않음
- PG 장애 시에도 Readiness Probe가 UP 상태로 유지될 수 있음

**개선 방안**:
```java
@Component
public class PaymentGatewayHealthIndicator implements HealthIndicator {
    
    private final PaymentGatewayClient paymentGatewayClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    
    @Override
    public Health health() {
        // Circuit Breaker 상태 확인
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentGatewayClient");
        CircuitBreaker.State state = circuitBreaker.getState();
        
        if (state == CircuitBreaker.State.OPEN) {
            return Health.down()
                .withDetail("circuitBreaker", "OPEN")
                .withDetail("reason", "PG 서비스가 장애 상태입니다")
                .build();
        }
        
        // 실제 PG 서비스 연결 테스트 (선택적)
        // 간단한 헬스체크 엔드포인트 호출 또는 Circuit Breaker 상태만 확인
        
        return Health.up()
            .withDetail("circuitBreaker", state.name())
            .withDetail("failureRate", circuitBreaker.getMetrics().getFailureRate())
            .build();
    }
}
```

**설정 추가** (`monitoring.yml`):
```yaml
management:
  health:
    readinessState:
      enabled: true
      include:
        - livenessState
        - paymentGateway # 커스텀 Health Indicator 추가
```

**효과**:
- PG 서비스가 장애 상태일 때 Readiness Probe가 DOWN 상태로 전환
- Kubernetes가 트래픽을 다른 인스턴스로 라우팅
- 자동 복구 지원

---

## Chapter 8 — Observability 개선 사항

### 현재 상태
- ✅ 로깅: 완벽하게 구현됨
- ⚠️ 메트릭: 부분적으로 구현됨 (Resilience4j 기본 메트릭만 노출)
- ❌ Distributed Tracing: 미구현

### 개선 필요 사항

#### 1. 커스텀 메트릭 추가 (높은 우선순위)

**문제점**:
- Resilience4j 기본 메트릭만 노출됨
- 비즈니스 로직 레벨의 커스텀 메트릭 없음
- Timeout/Fallback 발생 횟수를 명시적으로 추적하지 않음

**개선 방안**:

**1-1. Timeout/Fallback 발생 횟수 메트릭**

```java
@Component
public class PaymentGatewayMetrics {
    
    private final MeterRegistry meterRegistry;
    private final Counter timeoutCounter;
    private final Counter fallbackCounter;
    private final Counter callbackReceivedCounter;
    private final Counter orderStatusTransitionCounter;
    private final Timer paymentRequestTimer;
    
    public PaymentGatewayMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.timeoutCounter = Counter.builder("payment.gateway.timeout")
            .description("PG 결제 요청 타임아웃 발생 횟수")
            .tag("client", "paymentGatewayClient")
            .register(meterRegistry);
        
        this.fallbackCounter = Counter.builder("payment.gateway.fallback")
            .description("PG 결제 요청 Fallback 호출 횟수")
            .tag("client", "paymentGatewayClient")
            .register(meterRegistry);
        
        this.callbackReceivedCounter = Counter.builder("payment.callback.received")
            .description("PG 콜백 수신 횟수")
            .tag("status", "success")
            .register(meterRegistry);
        
        this.orderStatusTransitionCounter = Counter.builder("order.status.transition")
            .description("주문 상태 전이 횟수")
            .tag("from", "PENDING")
            .register(meterRegistry);
        
        this.paymentRequestTimer = Timer.builder("payment.gateway.request.duration")
            .description("PG 결제 요청 처리 시간")
            .tag("client", "paymentGatewayClient")
            .publishPercentiles(0.5, 0.95, 0.99) // P50, P95, P99
            .register(meterRegistry);
    }
    
    public void recordTimeout() {
        timeoutCounter.increment();
    }
    
    public void recordFallback() {
        fallbackCounter.increment();
    }
    
    public void recordCallbackReceived(String status) {
        callbackReceivedCounter.increment(
            Tags.of("status", status)
        );
    }
    
    public void recordOrderStatusTransition(String from, String to) {
        orderStatusTransitionCounter.increment(
            Tags.of("from", from, "to", to)
        );
    }
    
    public Timer.Sample startPaymentRequestTimer() {
        return Timer.start(meterRegistry);
    }
}
```

**1-2. PurchasingFacade에 메트릭 통합**

```java
@Component
public class PurchasingFacade {
    
    private final PaymentGatewayMetrics metrics;
    
    private String requestPaymentToGateway(...) {
        Timer.Sample sample = metrics.startPaymentRequestTimer();
        try {
            // PG 요청 로직
            return transactionKey;
        } catch (FeignException.TimeoutException e) {
            metrics.recordTimeout(); // 타임아웃 메트릭 기록
            throw e;
        } finally {
            sample.stop(metrics.getPaymentRequestTimer());
        }
    }
    
    public void handlePaymentCallback(...) {
        metrics.recordCallbackReceived(callbackRequest.status().name());
        
        if (verifiedStatus == TransactionStatus.SUCCESS) {
            metrics.recordOrderStatusTransition("PENDING", "COMPLETED");
        } else if (verifiedStatus == TransactionStatus.FAILED) {
            metrics.recordOrderStatusTransition("PENDING", "CANCELED");
        }
    }
}
```

**1-3. Fallback에서 메트릭 기록**

```java
@Component
public class PaymentGatewayClientFallback implements PaymentGatewayClient {
    
    private final PaymentGatewayMetrics metrics;
    
    @Override
    public ApiResponse<TransactionResponse> requestPayment(...) {
        metrics.recordFallback(); // Fallback 메트릭 기록
        // ... 기존 로직
    }
}
```

**효과**:
- Timeout/Fallback 발생 횟수를 명시적으로 추적 가능
- P50/P95/P99 latency 메트릭 제공
- 주문 상태 전이 추적 가능

---

#### 2. P50/P95/P99 Latency 메트릭 설정 (중간 우선순위)

**문제점**:
- HTTP 서버 요청에 대한 percentile-histogram은 설정되어 있음
- 하지만 PG 요청에 대한 명시적인 latency 메트릭이 없음

**개선 방안** (`monitoring.yml`):
```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        # PG 요청에 대한 latency 메트릭 추가
        payment.gateway.request.duration: true
      percentiles:
        payment.gateway.request.duration:
          p50: 0.5
          p95: 0.95
          p99: 0.99
      slo:
        payment.gateway.request.duration:
          - 2s  # 2초 이내 응답 목표
          - 5s  # 5초 이내 응답 목표
```

**효과**:
- PG 요청의 latency 분포를 명확히 파악 가능
- SLO 기반 알림 설정 가능

---

#### 3. Queue Depth & Pool Usage 메트릭 (중간 우선순위)

**문제점**:
- ThreadPool 사용률을 모니터링하지 않음
- Bulkhead 사용률은 Resilience4j 기본 메트릭으로 제공되지만, 명시적인 모니터링 설정 없음

**개선 방안**:

**3-1. ThreadPool 메트릭 추가**

```java
@Component
public class ThreadPoolMetrics {
    
    private final MeterRegistry meterRegistry;
    private final ExecutorService paymentGatewayThreadPool;
    
    @PostConstruct
    public void registerMetrics() {
        if (paymentGatewayThreadPool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) paymentGatewayThreadPool;
            
            Gauge.builder("thread.pool.payment.gateway.active", executor, 
                ThreadPoolExecutor::getActiveCount)
                .description("PG 전용 ThreadPool 활성 스레드 수")
                .register(meterRegistry);
            
            Gauge.builder("thread.pool.payment.gateway.queue.size", executor,
                e -> e.getQueue().size())
                .description("PG 전용 ThreadPool 큐 크기")
                .register(meterRegistry);
            
            Gauge.builder("thread.pool.payment.gateway.pool.size", executor,
                ThreadPoolExecutor::getPoolSize)
                .description("PG 전용 ThreadPool 풀 크기")
                .register(meterRegistry);
        }
    }
}
```

**효과**:
- ThreadPool 포화 상태를 사전에 감지 가능
- 리소스 사용률 모니터링 가능

---

#### 4. Distributed Tracing 구현 (낮은 우선순위)

**문제점**:
- 요청 추적 불가능
- 마이크로서비스 간 호출 추적 불가능

**개선 방안**:

**4-1. Micrometer Tracing 추가**

`build.gradle.kts`:
```kotlin
implementation("io.micrometer:micrometer-tracing-bridge-brave")
implementation("io.zipkin.reporter2:zipkin-reporter-brave")
```

**설정** (`application.yml`):
```yaml
management:
  tracing:
    sampling:
      probability: 1.0 # 100% 샘플링 (개발 환경)
```

**효과**:
- 요청 추적 가능
- 마이크로서비스 간 호출 추적 가능
- 성능 병목 지점 파악 가능

---

## 우선순위별 개선 계획

### 높은 우선순위 (즉시 구현 권장)

1. **PG 서비스 Health Indicator 구현**
   - Readiness Probe에 PG 상태 포함
   - Kubernetes 자동 복구 지원

2. **커스텀 메트릭 추가**
   - Timeout/Fallback 발생 횟수
   - 주문 상태 전이 추적
   - PG 요청 latency (P50/P95/P99)

### 중간 우선순위 (시간 있을 때 구현)

3. **ThreadPool 메트릭 추가**
   - ThreadPool 사용률 모니터링
   - Queue depth 모니터링

4. **P50/P95/P99 Latency 메트릭 설정**
   - SLO 기반 알림 설정

### 낮은 우선순위 (선택적)

5. **Distributed Tracing 구현**
   - Micrometer Tracing 추가
   - Zipkin/Jaeger 연동

---

## 구현 예시 코드

각 개선 사항에 대한 구체적인 구현 예시는 위에 제시된 코드를 참고하세요.

### Health Indicator 구현 예시
- `PaymentGatewayHealthIndicator.java`: PG 서비스 상태를 확인하는 커스텀 Health Indicator

### 메트릭 구현 예시
- `PaymentGatewayMetrics.java`: 커스텀 메트릭을 정의하고 기록하는 컴포넌트
- `PurchasingFacade.java`: 메트릭을 통합하는 비즈니스 로직

### 설정 예시
- `monitoring.yml`: 메트릭 설정 추가
- `application.yml`: Tracing 설정 추가

---

## 참고 자료

- [Spring Boot Actuator Health Indicators](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health)
- [Micrometer Custom Metrics](https://micrometer.io/docs/concepts)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)

