# Nice-to-Have Retry 기능 구현 검증 보고서

## 요구사항

### 🔁 Retry
- PG와 같은 필수 성공 API는 retry가 필요할 수 있음
- 하지만 retry는 비용이 크므로 타임아웃·CB와 조합 필요
- **비동기/배치 기반으로 retry 로직을 옮기면 베스트**

---

## 현재 구현 상태 분석

### 1. ⚠️ Retry 비활성화 상태

**위치**: `Resilience4jRetryConfig.java`, `application.yml`

**현재 설정**:
```java
// 결제 요청 API: 유저 요청 경로에서 사용되므로 Retry 비활성화 (빠른 실패)
RetryConfig noRetryConfig = RetryConfig.custom()
    .maxAttempts(1)  // 재시도 없음 (초기 시도만)
    .build();
retryRegistry.addConfiguration("paymentGatewayClient", noRetryConfig);
```

```yaml
resilience4j:
  retry:
    instances:
      paymentGatewayClient:
        maxAttempts: 1 # Retry 없음 (초기 시도만)
```

**검증 결과**: ⚠️ **Retry가 완전히 비활성화됨**
- 결제 요청 API: Retry 없음
- 조회 API: Retry 없음 (스케줄러에서 사용)
- 전체 `paymentGatewayClient`에 대해 Retry 비활성화

---

### 2. ✅ 타임아웃·CB와 조합

**타임아웃 설정**:
```yaml
feign:
  client:
    config:
      paymentGatewayClient:
        connectTimeout: 2000 # 연결 타임아웃 (2초)
        readTimeout: 6000 # 읽기 타임아웃 (6초)

resilience4j:
  timelimiter:
    instances:
      paymentGatewayClient:
        timeoutDuration: 6s
```

**Circuit Breaker 설정**:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentGatewayClient:
        failureRateThreshold: 50 # 실패율 임계값 (50%)
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
```

**검증 결과**: ✅ **타임아웃·CB와 조합됨**
- 타임아웃: 6초 (Feign readTimeout)
- Circuit Breaker: 실패율 50% 임계값
- Fallback: Circuit Breaker OPEN 시 Fallback 호출

---

### 3. ✅ 비동기/배치 기반 상태 복구

**스케줄러 구현**:
```java
@Scheduled(fixedDelay = 60000) // 1분마다 실행
public void recoverPendingOrders() {
    // PENDING 상태인 주문들 조회
    List<Order> pendingOrders = orderRepository.findAllByStatus(OrderStatus.PENDING);
    
    // 각 주문에 대해 PG 결제 상태 확인 API 호출
    for (Order order : pendingOrders) {
        purchasingFacade.recoverOrderStatusByPaymentCheck(userId, order.getId());
    }
}
```

**검증 결과**: ✅ **비동기/배치 기반 상태 복구 구현됨**
- 스케줄러에서 주기적으로 실행 (1분마다)
- PENDING 상태 주문들을 배치로 처리
- 유저 요청 스레드 점유 없음

**하지만**: ⚠️ **스케줄러에서 사용하는 조회 API에 Retry가 없음**
- `getTransactionsByOrder()` 호출 시 Retry 없음
- 일시적 오류 발생 시 다음 스케줄러 실행까지 대기 (최대 1분)

---

## Nice-to-Have 요구사항 충족도

| 항목 | 요구사항 | 현재 구현 | 평가 |
|------|---------|---------|------|
| PG 필수 성공 API에 Retry 필요 | ✅ 권장 | ⚠️ Retry 없음 | ⚠️ 부분적 |
| 타임아웃·CB와 조합 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| 비동기/배치 기반 Retry | ✅ 베스트 | ⚠️ Retry 없음 | ⚠️ 부분적 |

---

## 상세 분석

### 현재 구조

```
유저 요청 → createOrder() → requestPaymentToGateway() → PaymentGatewayClient.requestPayment()
                                                              ↑
                                                         [Retry 없음 - 최대 6초]
                                                         
스케줄러 → recoverPendingOrders() → recoverOrderStatusByPaymentCheck() → getTransactionsByOrder()
                                                                              ↑
                                                                         [Retry 없음 - 다음 스케줄러까지 대기]
```

### 권장 구조 (Nice-to-Have)

```
유저 요청 → createOrder() → requestPaymentToGateway() → PaymentGatewayClient.requestPayment()
                                                              ↑
                                                         [Retry 없음 - 빠른 실패]
                                                         
스케줄러 → recoverPendingOrders() → recoverOrderStatusByPaymentCheck() → getTransactionsByOrder()
                                                                              ↑
                                                                         [Retry 적용 - Exponential Backoff]
```

---

## 개선 방안

### 옵션 1: 스케줄러에서 사용하는 조회 API에 Retry 적용 (권장)

**목표**: 비동기/배치 기반으로 Retry 로직 적용

**방법 1: 별도 FeignClient 생성**
- 스케줄러 전용 FeignClient 생성
- 해당 클라이언트에만 Retry 적용

**방법 2: 메서드별 Retry 설정 (제한적)**
- Spring Cloud OpenFeign은 클라이언트 레벨 설정만 지원
- 메서드별 설정이 제대로 작동하지 않을 수 있음

**방법 3: 수동 Retry 로직 구현**
- 스케줄러에서 직접 Retry 로직 구현
- Exponential Backoff 수동 구현

**권장 방법**: **방법 1 (별도 FeignClient 생성)**

---

## 결론

### 현재 상태

**Nice-to-Have 요구사항 충족도**: **50%**

**구현된 부분**:
- ✅ 타임아웃·CB와 조합: 완벽하게 구현됨
- ✅ 비동기/배치 기반 상태 복구: 스케줄러 구현됨

**미구현 부분**:
- ⚠️ PG 필수 성공 API에 Retry: 현재 Retry 없음
- ⚠️ 비동기/배치 기반 Retry: 스케줄러에서 사용하는 조회 API에 Retry 없음

### 권장 사항

1. **스케줄러에서 사용하는 조회 API에 Retry 적용**
   - 별도 FeignClient 생성 (`PaymentGatewaySchedulerClient`)
   - Exponential Backoff 적용
   - 최대 3회 재시도

2. **유저 요청 경로는 Retry 없음 유지**
   - 빠른 실패 보장
   - 스레드 점유 최소화

3. **타임아웃·CB와 조합 유지**
   - 현재 설정 유지
   - Retry와 함께 사용

### 구현 우선순위

**Must-Have**: ✅ 완료
- Timeout: ✅ 구현됨
- Circuit Breaker: ✅ 구현됨
- Fallback: ✅ 구현됨

**Nice-to-Have**: ⚠️ 부분적 구현
- Retry: ⚠️ 비활성화 상태 (스케줄러에 적용 권장)

### 최종 평가

**현재 구현은 Must-Have 요구사항을 완벽하게 충족**하며, **Nice-to-Have 요구사항은 부분적으로 충족**합니다.

**핵심 설계 원칙 준수**:
- ✅ "실시간 API에서 긴 Retry는 하지 않는다" - 유저 요청 경로에서 Retry 없음
- ✅ "긴 작업은 비동기/배치에 위임한다" - 스케줄러에서 상태 복구
- ⚠️ "비동기/배치 기반으로 retry 로직을 옮기면 베스트" - 스케줄러에 Retry 미적용

**개선 여지**:
- 스케줄러에서 사용하는 조회 API에 Retry 적용하면 Nice-to-Have 요구사항을 완전히 충족할 수 있습니다.

