# 긴 Retry 패턴 반영 검증 보고서

## 실무 권장 패턴 가치관

### 핵심 원칙
> **"실시간 API에서 긴 Retry는 하지 않는다. 긴 작업은 비동기/배치에 위임한다."**

### 문제 상황
- PG 응답 타임아웃이 30초 같은 경우, Retry는 어떻게 할까?
- 이런 장기 지연은 유저 요청 스레드에서는 감당 불가
- 실무에서는 Retry를 실시간 API에서 하지 않음

### 해결 전략
1. **유저 요청에서 "결제 중" 반환**
2. **배치·스케줄러에서 PG 상태 조회로 완료 처리**

---

## 현재 구현 상태 분석

### 1. ⚠️ 유저 요청 경로에서 Retry 적용

**위치**: `PaymentGatewayClient.requestPayment()` (FeignClient 레벨)

**현재 설정**:
- **타임아웃**: 6초 (readTimeout)
- **Retry**: 최대 3회 (초기 시도 포함)
- **Exponential Backoff**: 500ms → 1000ms (최대 5초)

**최악의 경우 스레드 점유 시간 계산**:
```
초기 시도: 6초 (타임아웃)
재시도1: 500ms 대기 + 6초 (타임아웃) = 6.5초
재시도2: 1000ms 대기 + 6초 (타임아웃) = 7초
총 소요 시간: 약 20초
```

**검증 결과**: ⚠️ **권장 패턴과 부분적으로 일치하지 않음**
- 유저 요청 스레드에서 최대 약 20초 점유 가능
- PG 응답이 30초로 늦어지면 더 길어질 수 있음
- 스레드 풀 고갈 위험

---

### 2. ✅ 유저 요청에서 "결제 중" 반환

**위치**: `PurchasingFacade.createOrder()`

**구현 내용**:
```java
Order savedOrder = orderRepository.save(order);
// 주문은 PENDING 상태로 저장됨

// PG 결제 요청 (비동기)
try {
    String transactionKey = requestPaymentToGateway(...);
    // 성공/실패와 관계없이 주문은 PENDING 상태로 유지
} catch (Exception e) {
    // 예외 발생 시에도 주문은 PENDING 상태로 유지
}

return OrderInfo.from(savedOrder); // PENDING 상태로 반환
```

**검증 결과**: ✅ **완벽하게 구현됨**
- 유저 요청에서 주문을 PENDING 상태로 반환
- PG 요청 성공/실패와 관계없이 빠르게 응답
- 유저는 "결제 중" 상태를 받음

---

### 3. ✅ 배치·스케줄러에서 PG 상태 조회로 완료 처리

**위치**: `PaymentRecoveryScheduler.recoverPendingOrders()`

**구현 내용**:
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

**검증 결과**: ✅ **완벽하게 구현됨**
- 스케줄러에서 주기적으로 PENDING 주문 조회
- PG 상태 조회 API로 완료 처리
- 유저 요청 스레드 점유 없음

---

## 문제점 및 개선 방안

### ⚠️ 문제: 유저 요청 경로에서 Retry 사용

**현재 구조**:
```
유저 요청 → createOrder() → requestPaymentToGateway() → PaymentGatewayClient.requestPayment()
                                                              ↑
                                                         [Retry 적용 - 최대 20초]
                                                         
스케줄러 → recoverPendingOrders() → recoverOrderStatusByPaymentCheck() → getTransactionsByOrder()
                                                                              ↑
                                                                         [Retry 적용 - 안전]
```

**권장 구조**:
```
유저 요청 → createOrder() → requestPaymentToGateway() → PaymentGatewayClient.requestPayment()
                                                              ↑
                                                         [Retry 없음, 빠른 실패 - 최대 6초]
                                                         
스케줄러 → recoverPendingOrders() → recoverOrderStatusByPaymentCheck() → getTransactionsByOrder()
                                                                              ↑
                                                                         [Retry 적용 - 안전]
```

### 개선 방안

#### 옵션 1: 결제 요청 API에서 Retry 제거 (권장)
- 유저 요청 경로에서는 Retry 없이 빠르게 실패
- 타임아웃 발생 시 즉시 주문을 PENDING 상태로 반환
- 스케줄러에서 주기적으로 상태 복구 (Retry 적용)

**장점**:
- 유저 요청 스레드 점유 최소화 (최대 6초)
- 빠른 응답 시간 보장
- 스레드 풀 고갈 방지
- PG 응답이 30초로 늦어져도 유저 요청은 빠르게 응답

**단점**:
- 결제 요청이 즉시 실패할 수 있음 (하지만 주문은 PENDING 상태로 유지되어 나중에 복구 가능)

---

## 검증 요약

| 항목 | 권장 패턴 | 현재 구현 | 평가 |
|------|---------|---------|------|
| 유저 요청에서 "결제 중" 반환 | ✅ 권장 | ✅ 구현됨 | ✅ 완벽 |
| 배치·스케줄러에서 상태 조회 | ✅ 권장 | ✅ 구현됨 | ✅ 완벽 |
| 실시간 API에서 긴 Retry 없음 | ✅ 권장 | ⚠️ 적용됨 | ⚠️ 개선 필요 |
| 긴 작업을 비동기/배치에 위임 | ✅ 권장 | ✅ 구현됨 | ✅ 완벽 |

---

## 결론

**현재 구현 상태**:
- ✅ 유저 요청에서 "결제 중" 반환: 완벽하게 구현됨
- ✅ 배치·스케줄러에서 상태 조회: 완벽하게 구현됨
- ⚠️ 실시간 API에서 긴 Retry: 유저 요청 경로에서 Retry 적용 (최대 약 20초)

**권장 패턴 준수도**: **75%**

**핵심 가치관 반영**:
- ✅ "유저 요청에서 '결제 중' 반환" - PENDING 상태로 반환
- ✅ "배치·스케줄러에서 PG 상태 조회로 완료 처리" - 스케줄러에서 상태 복구
- ⚠️ "실시간 API에서 긴 Retry는 하지 않는다" - 유저 요청 경로에서 Retry 사용 (최대 약 20초)

**개선 권장 사항**:
1. **결제 요청 API에서 Retry 제거**
   - 유저 요청 경로에서는 Retry 없이 빠르게 실패 (최대 6초)
   - 실패 시 주문을 PENDING 상태로 유지
   - 스케줄러에서 주기적으로 상태 복구 (Retry 적용)

**현재 구현의 장점**:
- 주문은 PENDING 상태로 반환되어 유저는 빠르게 응답받음
- 스케줄러에서 상태 복구가 이루어져 최종 정합성 보장
- 하지만 유저 요청 스레드에서 최대 약 20초 점유 가능 (개선 필요)

**시나리오별 분석**:

1. **PG 응답이 정상 (1~5초)**:
   - 현재: 초기 시도로 성공 → 빠른 응답 ✅
   - 개선 후: 초기 시도로 성공 → 빠른 응답 ✅

2. **PG 응답이 지연 (6초 이상)**:
   - 현재: 타임아웃 발생 → Retry 시도 (최대 약 20초) ⚠️
   - 개선 후: 타임아웃 발생 → 즉시 실패 (약 6초) → 스케줄러에서 복구 ✅

3. **PG 응답이 매우 지연 (30초)**:
   - 현재: 타임아웃 발생 → Retry 시도 (최대 약 20초) → 여전히 실패 → 스케줄러에서 복구 ⚠️
   - 개선 후: 타임아웃 발생 → 즉시 실패 (약 6초) → 스케줄러에서 복구 ✅

