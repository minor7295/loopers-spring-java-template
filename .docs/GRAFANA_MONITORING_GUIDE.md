# Grafana 모니터링 가이드

## 개요

이 가이드는 Resilience4j Circuit Breaker, Bulkhead, Retry 메트릭을 Grafana 대시보드에서 모니터링하는 방법을 설명합니다.

## 사전 준비

### 1. 모니터링 스택 실행

```bash
cd docker
docker-compose -f monitoring-compose.yml up -d
```

### 2. 애플리케이션 실행

애플리케이션을 실행하고 Actuator 엔드포인트가 활성화되어 있는지 확인합니다:

```bash
# 애플리케이션 실행 후
curl http://localhost:8081/actuator/prometheus | grep resilience4j
```

## Grafana 접속

1. **URL**: http://localhost:3000
2. **사용자명**: `admin`
3. **비밀번호**: `admin`

## 대시보드 확인

Grafana에 접속하면 자동으로 다음 대시보드가 로드됩니다:

- **Resilience4j Circuit Breaker & Bulkhead Dashboard**
  - Circuit Breaker 상태
  - Circuit Breaker 호출 수
  - 실패율
  - Bulkhead 사용률
  - Retry 시도 횟수

## Circuit Breaker를 열리게 하는 테스트 실행

### 방법 1: IDE에서 실행

1. `CircuitBreakerLoadTest` 클래스를 엽니다
2. 다음 테스트 중 하나를 실행합니다:
   - `triggerCircuitBreakerOpen_withConsecutiveFailures`: 연속 실패를 유발하여 Circuit Breaker를 OPEN 상태로 만듭니다
   - `triggerCircuitBreakerOpen_withConcurrentRequests`: 동시 요청을 보내어 Circuit Breaker를 OPEN 상태로 만듭니다
   - `verifyFallback_whenCircuitBreakerOpen`: Circuit Breaker가 OPEN 상태일 때 Fallback이 동작하는지 확인합니다

### 방법 2: Gradle로 실행

```bash
./gradlew :apps:commerce-api:test --tests CircuitBreakerLoadTest.triggerCircuitBreakerOpen_withConsecutiveFailures
```

### 방법 3: CircuitBreakerTestUtil 사용

테스트 코드에서 `CircuitBreakerTestUtil`을 사용하여 Circuit Breaker를 직접 제어할 수 있습니다:

```java
@Autowired
private CircuitBreakerTestUtil circuitBreakerTestUtil;

@Test
void test() {
    // Circuit Breaker를 OPEN 상태로 전환
    circuitBreakerTestUtil.openCircuitBreaker("paymentGatewayClient");
    
    // 상태 확인
    assertThat(circuitBreakerTestUtil.isCircuitBreakerOpen("paymentGatewayClient")).isTrue();
    
    // 리셋
    circuitBreakerTestUtil.resetCircuitBreaker("paymentGatewayClient");
}
```

## Grafana에서 확인할 수 있는 메트릭

### Circuit Breaker 메트릭

- **resilience4j_circuitbreaker_state**: Circuit Breaker 상태 (0: CLOSED, 1: OPEN, 2: HALF_OPEN)
- **resilience4j_circuitbreaker_calls_total**: Circuit Breaker 호출 수 (successful, failed, not_permitted)
- **resilience4j_circuitbreaker_failure_rate**: 실패율
- **resilience4j_circuitbreaker_slow_calls_total**: 느린 호출 수
- **resilience4j_circuitbreaker_not_permitted_calls_total**: Circuit Open 상태에서 차단된 호출 수

### Bulkhead 메트릭

- **resilience4j_bulkhead_available_concurrent_calls**: 사용 가능한 동시 호출 수
- **resilience4j_bulkhead_max_allowed_concurrent_calls**: 최대 허용 동시 호출 수

### Retry 메트릭

- **resilience4j_retry_calls_total**: Retry 호출 수 (successful_without_retry, successful_with_retry, failed_with_retry, failed_without_retry)

## 대시보드 패널 설명

### 1. Circuit Breaker State
- Circuit Breaker의 현재 상태를 색상으로 표시
- 녹색: CLOSED (정상)
- 빨간색: OPEN (장애)
- 노란색: HALF_OPEN (복구 시도 중)

### 2. Circuit Breaker Calls
- 시간에 따른 Circuit Breaker 호출 수 그래프
- successful, failed, not_permitted 호출을 구분하여 표시

### 3. Circuit Breaker Failure Rate
- 실패율 그래프
- 50% 이상이면 Circuit Breaker가 OPEN 상태로 전환됨

### 4. Bulkhead Available Concurrent Calls
- 사용 가능한 동시 호출 수
- 0에 가까우면 Bulkhead가 포화 상태

### 5. Bulkhead Thread Pool Usage
- Bulkhead 사용률 그래프
- Used vs Available 동시 호출 수

### 6. Retry Attempts
- Retry 시도 횟수 그래프
- successful_with_retry, failed_with_retry 등을 구분하여 표시

### 7. Circuit Breaker Slow Calls
- 느린 호출 수 그래프
- 2초 이상 걸리는 호출을 감지

### 8. Circuit Breaker Not Permitted Calls
- Circuit Open 상태에서 차단된 호출 수
- Fallback이 호출된 횟수를 나타냄

## 테스트 시나리오

### 시나리오 1: 연속 실패로 Circuit Breaker 열기

1. `triggerCircuitBreakerOpen_withConsecutiveFailures` 테스트 실행
2. Grafana 대시보드에서 다음을 관찰:
   - Circuit Breaker State가 CLOSED → OPEN으로 변화
   - Failure Rate가 50% 이상으로 증가
   - Not Permitted Calls가 증가

### 시나리오 2: 동시 요청으로 Circuit Breaker 열기

1. `triggerCircuitBreakerOpen_withConcurrentRequests` 테스트 실행
2. Grafana 대시보드에서 다음을 관찰:
   - Bulkhead 사용률이 증가
   - Circuit Breaker가 빠르게 OPEN 상태로 전환

### 시나리오 3: Fallback 동작 확인

1. `verifyFallback_whenCircuitBreakerOpen` 테스트 실행
2. Grafana 대시보드에서 다음을 관찰:
   - Not Permitted Calls가 증가
   - Circuit Breaker State가 OPEN 상태 유지

## 문제 해결

### Prometheus가 메트릭을 수집하지 않는 경우

1. Prometheus UI 확인: http://localhost:9090
2. Status → Targets에서 `spring-boot-app` 타겟이 UP 상태인지 확인
3. 애플리케이션이 실행 중이고 포트가 올바른지 확인:
   ```bash
   curl http://localhost:8081/actuator/prometheus
   ```

### Grafana에서 대시보드가 보이지 않는 경우

1. Grafana 로그 확인:
   ```bash
   docker logs docker-grafana-1
   ```

2. 대시보드 파일 확인:
   - `docker/grafana/dashboards/resilience4j-circuit-breaker.json` 파일 존재 확인
   - `docker/grafana/provisioning/dashboards/dashboard.yml` 설정 확인

3. Grafana 재시작:
   ```bash
   docker-compose -f monitoring-compose.yml restart grafana
   ```

### Circuit Breaker가 열리지 않는 경우

1. Circuit Breaker 설정 확인 (`application.yml`):
   - `slidingWindowSize`: 20
   - `minimumNumberOfCalls`: 5
   - `failureRateThreshold`: 50

2. 충분한 실패를 유발했는지 확인:
   - 최소 5번 호출 중 3번 이상 실패해야 함
   - 테스트에서 10번 이상 호출하는 것을 권장

3. Circuit Breaker 상태 확인:
   ```java
   CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentGatewayClient");
   System.out.println("State: " + circuitBreaker.getState());
   System.out.println("Metrics: " + circuitBreaker.getMetrics());
   ```

## 참고 자료

- [Resilience4j 공식 문서](https://resilience4j.readme.io/)
- [Grafana 대시보드 가이드](https://grafana.com/docs/grafana/latest/dashboards/)
- [Prometheus 쿼리 가이드](https://prometheus.io/docs/prometheus/latest/querying/basics/)

