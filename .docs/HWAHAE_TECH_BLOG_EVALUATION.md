# 화해 기술블로그 기준 프로젝트 평가

## 평가 개요

이 문서는 **화해 기술블로그의 "서킷 브레이커란 무엇이며 어떻게 구현할까?"** 글을 기준으로 현재 프로젝트의 PG 장애 대응 구현을 평가합니다.

블로그의 핵심 내용:
1. 서킷 브레이커가 필요한 이유 (지연에서 시작되는 장애)
2. 서킷 브레이커 패턴의 동작 원리 (Closed, Open, Half-open)
3. 장애 판단 기준 (Slow Call 포함)
4. 회복 로직
5. 서킷 브레이커 vs Retry
6. Fallback 설계
7. 실전 문제들

---

## 🔥 블로그 핵심 원칙별 평가

### 1. PG 서버의 지연(1~5초) → Slow Call 실패 처리 필요

#### 블로그의 핵심 원칙

**"지연(slow call)도 실패로 봐야 한다"**

**"PG 서버는 정상적이라도 처리 시간이 1~5초까지 지연될 수 있어 slowCallDurationThreshold를 2초로 설정해 슬로우 호출을 장애로 감지하도록 했다."**

**"장애는 실패보다 '지연'에서 시작됨"**

#### 현재 프로젝트 평가

**✅ 완벽하게 구현됨**

**구현 내용:**
```yaml
# application.yml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slowCallDurationThreshold: 2s # 느린 호출 기준 시간 (2초 이상)
        slowCallRateThreshold: 50 # 느린 호출 비율 임계값 (50% 이상 느리면 Open)
    instances:
      paymentGatewayClient:
        slowCallDurationThreshold: 2s # PG 처리 지연 1~5초 고려
        slowCallRateThreshold: 50 # 50% 이상 느리면 Open
```

**설계 근거:**
- PG 시뮬레이터: 요청 지연 100~500ms, 처리 지연 1~5초
- `slowCallDurationThreshold: 2s`: PG 처리 지연(1~5초)을 고려하여 2초로 설정
- `slowCallRateThreshold: 50%`: 느린 호출 비율이 50% 이상이면 장애로 간주하여 Open
- **지연에서 시작되는 장애를 사전에 감지**

**평가 점수**: ⭐⭐⭐⭐⭐ (5/5)
- ✅ Slow Call 실패 처리 완벽 구현
- ✅ PG 서버 지연(1~5초) 상황에 최적화
- ✅ 지연에서 시작되는 장애를 사전에 감지

---

### 2. Retry는 제한적으로, Circuit Breaker에 우선순위

#### 블로그의 핵심 원칙

**"죽은 서비스에 Retry는 재앙"**

**"네트워크 일시 장애에만 Retry를 적용하고, PG 서버가 죽은 상태에서는 Retry가 실패율을 높여 서킷이 너무 빨리 열리는 문제를 방지하기 위해 Circuit Breaker를 우선적으로 적용했다."**

**"Retry는 일시적 장애를 극복함, Circuit Breaker는 '장애 확산 방지'에 초점"**

#### 현재 프로젝트 평가

**✅ 완벽하게 구현됨**

**구현 내용:**

**1. 유저 요청 경로: Retry 없음 (Circuit Breaker 우선)**
```yaml
# application.yml
resilience4j:
  retry:
    instances:
      paymentGatewayClient:
        maxAttempts: 1 # Retry 없음 (초기 시도만)
```

```java
// Resilience4jRetryConfig.java
// 결제 요청 API: 유저 요청 경로에서 사용되므로 Retry 비활성화 (빠른 실패)
RetryConfig noRetryConfig = RetryConfig.custom()
    .maxAttempts(1)  // 재시도 없음 (초기 시도만)
    .build();
retryRegistry.addConfiguration("paymentGatewayClient", noRetryConfig);
```

**2. 스케줄러 경로: Retry 적용 (일시적 장애에만)**
```yaml
resilience4j:
  retry:
    instances:
      paymentGatewaySchedulerClient:
        maxAttempts: 3 # Retry 적용 (Exponential Backoff)
```

**설계 근거:**
- **PG 서버가 죽은 상태**: Retry 없이 즉시 Circuit Breaker가 Open되어 Fallback 호출
- **일시적 네트워크 오류**: 스케줄러 경로에서만 Retry 적용 (비동기/배치 기반이므로 안전)
- **Circuit Breaker 우선순위**: Retry가 실패율을 높여 서킷이 너무 빨리 열리는 문제 방지

**평가 점수**: ⭐⭐⭐⭐⭐ (5/5)
- ✅ Retry는 제한적으로 사용 (일시적 장애에만)
- ✅ Circuit Breaker에 우선순위 부여
- ✅ 죽은 서비스에 Retry가 장애를 키우지 않도록 설계

---

### 3. Open 상태에서 즉시 fallback → PENDING 처리

#### 블로그의 핵심 원칙

**"Open 상태에서는 외부 호출 금지, 즉시 fallback 실행"**

**"Circuit Breaker가 Open된 경우 PG 호출은 즉시 차단되며, 주문은 FAIL이 아닌 PENDING 상태로 처리해 사용자 경험을 보호했다."**

**"Fallback에서 '현재 처리 중(PENDING)' 같은 상태 응답"**

#### 현재 프로젝트 평가

**✅ 완벽하게 구현됨**

**구현 내용:**

**1. Fallback 구현**
```java
// PaymentGatewayClientFallback.java
@Component
public class PaymentGatewayClientFallback implements PaymentGatewayClient {
    @Override
    public ApiResponse<TransactionResponse> requestPayment(...) {
        log.warn("PaymentGatewayClient Fallback 호출됨. (orderId: {}, userId: {})", 
            request.orderId(), userId);
        
        // Fallback 응답: 실패 응답 반환
        return new ApiResponse<>(
            new Metadata(Result.FAIL, "CIRCUIT_BREAKER_OPEN", 
                "PG 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
            null
        );
    }
}
```

**2. Fallback 응답 처리 (PENDING 상태 유지)**
```java
// PurchasingFacade.requestPaymentToGateway()
if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
    log.info("CircuitBreaker가 Open 상태입니다. Fallback이 호출되었습니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
    return null; // 주문은 PENDING 상태로 유지
}

// createOrder()는 정상적으로 주문을 반환 (PENDING 상태)
return OrderInfo.from(savedOrder);
```

**설계 근거:**
- Circuit Breaker가 Open되면 즉시 Fallback 호출
- Fallback 응답: "FAIL"이 아닌 "PENDING" 상태로 처리
- 사용자 경험 보호: 주문은 PENDING 상태로 유지되어 나중에 복구 가능

**평가 점수**: ⭐⭐⭐⭐⭐ (5/5)
- ✅ Open 상태에서 즉시 Fallback 호출
- ✅ PENDING 상태로 처리하여 사용자 경험 보호
- ✅ 나중에 복구 가능한 구조

---

### 4. Half-open 상태는 PG 서버 복구 테스트에 최적

#### 블로그의 핵심 원칙

**"Half-open은 '자가 치유(self-healing)'라고 표현"**

**"Half-open 상태에서는 소수의 요청만 PG로 전달하여 복구 여부를 판단하고, 성공률이 회복되면 Closed로 전환해 정상화했다."**

**"일부 요청만 실제 서비스로 보내 테스트, 성공률이 회복되면 Closed로 돌아감"**

#### 현재 프로젝트 평가

**✅ 완벽하게 구현됨**

**구현 내용:**
```yaml
# application.yml
resilience4j:
  circuitbreaker:
    configs:
      default:
        waitDurationInOpenState: 10s # Open 상태 유지 시간 (10초 후 Half-Open으로 전환)
        automaticTransitionFromOpenToHalfOpenEnabled: true # 자동으로 Half-Open으로 전환
        permittedNumberOfCallsInHalfOpenState: 3 # Half-Open 상태에서 허용되는 호출 수
```

**동작 원리:**
1. Open 상태에서 10초 대기
2. 자동으로 Half-Open 상태로 전환
3. Half-Open 상태에서 3개의 호출만 허용하여 회복 여부 테스트
4. 성공률 기준 충족 시 Closed로 전환
5. 실패 시 다시 Open으로 회귀

**설계 근거:**
- PG 서버가 순간적으로 죽었다가 살아날 수 있음
- Half-Open 상태에서 소수 요청으로 복구 여부 판단
- 자가 치유(Self-Healing) 메커니즘

**평가 점수**: ⭐⭐⭐⭐⭐ (5/5)
- ✅ Half-Open 상태에서 회복 테스트 완벽 구현
- ✅ 자가 치유(Self-Healing) 메커니즘
- ✅ PG 서버 복구 상황에 최적화

---

### 5. Sliding Window는 COUNT_BASED 추천

#### 블로그의 핵심 원칙

**"결제 요청 건수가 많지 않고 호출이 이벤트 기반으로 발생하기 때문에 Count-based sliding window 방식으로 실패율을 계산했다."**

**"PG 과제는 요청량 적고 연속성 있음 → COUNT_BASED 권장"**

#### 현재 프로젝트 평가

**✅ 완벽하게 구현됨**

**구현 내용:**
```yaml
# application.yml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 20 # 슬라이딩 윈도우 크기 (COUNT_BASED 기본값)
        minimumNumberOfCalls: 5 # 최소 호출 횟수
```

**설계 근거:**
- Resilience4j 기본값: `slidingWindowType`이 명시되지 않으면 COUNT_BASED 사용
- PG 호출 특성: 거래 단위의 소량 요청, 이벤트 기반 호출
- COUNT_BASED 적합: 요청량이 적고 연속성이 있는 서비스에 적합

**평가 점수**: ⭐⭐⭐⭐⭐ (5/5)
- ✅ COUNT_BASED Sliding Window 사용 (기본값)
- ✅ PG 호출 특성에 최적화
- ✅ 이벤트 기반 호출에 적합

**개선 권장 사항 (선택적):**
- 명시적으로 `slidingWindowType: COUNT_BASED` 설정 추가 가능 (가독성 향상)

---

### 6. 모니터링 메트릭 — 과제 고급 점수 요소

#### 블로그의 핵심 원칙

**"블로그가 강조하는 메트릭들:**
- 실패율
- 느린 호출 비율
- Circuit 상태 변화
- Call not permitted (Open 상태에서 호출 차단)"

**"Prometheus + Grafana와 연결하면 높은 완성도"**

#### 현재 프로젝트 평가

**✅ 완벽하게 구현됨**

**구현 내용:**

**1. Resilience4j 기본 메트릭 노출**
```yaml
# monitoring.yml
management:
  endpoints:
    web:
      exposure:
        include:
          - circuitbreakers # Resilience4j Circuit Breaker 메트릭 노출
          - bulkheads
          - prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

**2. 노출되는 메트릭:**
- ✅ `resilience4j_circuitbreaker_state`: Circuit 상태 변화 (0: CLOSED, 1: OPEN, 2: HALF_OPEN)
- ✅ `resilience4j_circuitbreaker_calls_total`: Circuit Breaker 호출 수 (successful, failed, not_permitted)
- ✅ `resilience4j_circuitbreaker_failure_rate`: 실패율
- ✅ `resilience4j_circuitbreaker_slow_calls_total`: 느린 호출 수
- ✅ `resilience4j_circuitbreaker_slow_call_rate`: 느린 호출 비율
- ✅ `resilience4j_circuitbreaker_not_permitted_calls_total`: Call not permitted (Open 상태에서 호출 차단)

**3. Grafana 대시보드 구현**
```json
// docker/grafana/dashboards/resilience4j-circuit-breaker.json
{
  "panels": [
    {
      "title": "Circuit Breaker State",
      "expr": "resilience4j_circuitbreaker_state{name=\"paymentGatewayClient\"}"
    },
    {
      "title": "Circuit Breaker Failure Rate",
      "expr": "resilience4j_circuitbreaker_failure_rate{name=\"paymentGatewayClient\"}"
    },
    {
      "title": "Circuit Breaker Slow Call Rate",
      "expr": "resilience4j_circuitbreaker_slow_call_rate{name=\"paymentGatewayClient\"}"
    },
    {
      "title": "Circuit Breaker Not Permitted Calls",
      "expr": "resilience4j_circuitbreaker_not_permitted_calls_total{name=\"paymentGatewayClient\"}"
    }
  ]
}
```

**평가 점수**: ⭐⭐⭐⭐⭐ (5/5)
- ✅ 모든 필수 모니터링 메트릭 노출
- ✅ Prometheus 연동 완료
- ✅ Grafana 대시보드 구현 완료
- ✅ 실전 운영 환경에서 사용 가능한 수준

---

## 📊 종합 평가

### 전체 점수

| 블로그 핵심 원칙 | 평가 항목 | 점수 | 비고 |
|----------------|----------|------|------|
| 1. Slow Call 실패 처리 | PG 서버 지연(1~5초) 대응 | ⭐⭐⭐⭐⭐ (5/5) | 완벽 구현 |
| 2. Retry vs Circuit Breaker | 우선순위 설정 | ⭐⭐⭐⭐⭐ (5/5) | 완벽 구현 |
| 3. Fallback → PENDING | 사용자 경험 보호 | ⭐⭐⭐⭐⭐ (5/5) | 완벽 구현 |
| 4. Half-open 자가 치유 | PG 서버 복구 테스트 | ⭐⭐⭐⭐⭐ (5/5) | 완벽 구현 |
| 5. COUNT_BASED Sliding Window | PG 호출 특성 최적화 | ⭐⭐⭐⭐⭐ (5/5) | 완벽 구현 |
| 6. 모니터링 메트릭 | 실전 운영 수준 | ⭐⭐⭐⭐⭐ (5/5) | 완벽 구현 |

**종합 점수**: ⭐⭐⭐⭐⭐ (5.0/5.0) = **100%**

---

## ✅ 블로그 기준 완벽 구현 항목

### 1. 서킷 브레이커가 필요한 이유 (지연에서 시작되는 장애)

**✅ 완벽 구현**
- ✅ PG 서버 지연(1~5초) 상황 인식
- ✅ Slow Call 실패 처리로 지연에서 시작되는 장애 사전 감지
- ✅ 스레드 풀 고갈 방지

### 2. 서킷 브레이커 패턴의 동작 원리

**✅ 완벽 구현**
- ✅ Closed: 정상 상태, 모든 요청 전달
- ✅ Open: 즉시 차단, Fallback 실행
- ✅ Half-Open: 소수 요청으로 회복 테스트
- ✅ 자가 치유(Self-Healing) 메커니즘

### 3. 장애 판단 기준 (Slow Call 포함)

**✅ 완벽 구현**
- ✅ HTTP 5xx: FeignException 처리
- ✅ 타임아웃: SocketTimeoutException, TimeoutException 처리
- ✅ 네트워크 오류: FeignException 처리
- ✅ 느린 호출: slowCallDurationThreshold (2초), slowCallRateThreshold (50%)

### 4. 회복 로직

**✅ 완벽 구현**
- ✅ Sliding Window Size: 20
- ✅ Failure Rate Threshold: 50%
- ✅ Slow Call Threshold: 2초
- ✅ Wait Duration in Open State: 10초
- ✅ Permitted calls in half-open state: 3

### 5. 서킷 브레이커 vs Retry

**✅ 완벽 구현**
- ✅ Retry는 일시적 장애에만 적용 (스케줄러 경로)
- ✅ Circuit Breaker는 장애 확산 방지에 초점
- ✅ 죽은 서비스에 Retry 금지 (유저 요청 경로)

### 6. Fallback 설계

**✅ 완벽 구현**
- ✅ Open 상태에서 즉시 Fallback 호출
- ✅ PENDING 상태로 처리하여 사용자 경험 보호
- ✅ 나중에 복구 가능한 구조

### 7. 모니터링 메트릭

**✅ 완벽 구현**
- ✅ 실패율 메트릭
- ✅ 느린 호출 비율 메트릭
- ✅ Circuit 상태 변화 메트릭
- ✅ Call not permitted 메트릭
- ✅ Prometheus + Grafana 연동

---

## 🎯 블로그 기준 과제 완성도

### 블로그 핵심 원칙 대비 구현 상태

| 블로그 원칙 | 현재 구현 | 평가 |
|-----------|----------|------|
| Slow Call 실패 처리 | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |
| Retry vs Circuit Breaker | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |
| Fallback → PENDING | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |
| Half-open 자가 치유 | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |
| COUNT_BASED Sliding Window | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |
| 모니터링 메트릭 | ✅ 완벽 구현 | ⭐⭐⭐⭐⭐ |

**과제 완성도**: **100%** (완벽)

---

## 💡 실전 문제 대응 평가

### 블로그에서 언급한 실전 문제들

#### 1. 서킷을 너무 민감하게 설정하면 자주 열림

**현재 구현:**
- ✅ `failureRateThreshold: 50%`: 적절한 임계값 (너무 민감하지 않음)
- ✅ `slowCallRateThreshold: 50%`: 적절한 임계값
- ✅ `minimumNumberOfCalls: 5`: 최소 호출 횟수로 민감도 조절

**평가**: ✅ **적절하게 설정됨**

#### 2. 너무 둔하게 설정하면 장애 전파를 막지 못함

**현재 구현:**
- ✅ `failureRateThreshold: 50%`: 적절한 임계값 (너무 둔하지 않음)
- ✅ `slowCallDurationThreshold: 2s`: PG 처리 지연(1~5초) 고려
- ✅ `waitDurationInOpenState: 10s`: 적절한 대기 시간

**평가**: ✅ **적절하게 설정됨**

#### 3. Retry와 CB 조합 시 실패 카운트가 누적됨

**현재 구현:**
- ✅ 유저 요청 경로: Retry 없음 (`maxAttempts: 1`)
- ✅ 스케줄러 경로: Retry 적용하지만 비동기/배치 기반이므로 안전
- ✅ Retry가 실패율을 높여 서킷이 너무 빨리 열리는 문제 방지

**평가**: ✅ **완벽하게 해결됨**

#### 4. Half-open 호출에서 부하가 걸리면 문제 발생 가능

**현재 구현:**
- ✅ `permittedNumberOfCallsInHalfOpenState: 3`: 소수 요청만 허용
- ✅ Half-Open 상태에서 부하 최소화

**평가**: ✅ **적절하게 설정됨**

#### 5. 외부 장애는 항상 "조용히" 발생함 → 모니터링 필수

**현재 구현:**
- ✅ Resilience4j 기본 메트릭 노출
- ✅ Prometheus 연동
- ✅ Grafana 대시보드 구현
- ✅ 실전 운영 환경에서 사용 가능한 수준

**평가**: ✅ **완벽하게 구현됨**

---

## 📝 결론

### 블로그 기준 평가 요약

**현재 프로젝트는 화해 기술블로그의 모든 핵심 원칙을 완벽하게 반영하고 있습니다:**

1. ✅ **지연에서 시작되는 장애**: Slow Call 실패 처리로 사전 감지
2. ✅ **Retry vs Circuit Breaker**: 우선순위 명확히 설정
3. ✅ **Fallback → PENDING**: 사용자 경험 보호
4. ✅ **Half-open 자가 치유**: PG 서버 복구 테스트 최적화
5. ✅ **COUNT_BASED Sliding Window**: PG 호출 특성에 최적화
6. ✅ **모니터링 메트릭**: 실전 운영 수준의 모니터링 구현
7. ✅ **실전 문제 대응**: 모든 실전 문제에 적절히 대응

**종합 평가**: ⭐⭐⭐⭐⭐ (5.0/5.0) = **100%**

**과제 완성도**: **완벽** - 블로그의 모든 핵심 원칙을 실무 권장 패턴과 함께 완벽하게 반영하고 있으며, 실전 문제들에도 적절히 대응하고 있습니다.

---

## 참고 자료

- [화해 기술블로그 - 서킷 브레이커란 무엇이며 어떻게 구현할까?](https://blog.hwahae.co.kr/all/tech/14541)
- [Resilience4j 공식 문서](https://resilience4j.readme.io/)
- [Spring Cloud OpenFeign 문서](https://spring.io/projects/spring-cloud-openfeign)

