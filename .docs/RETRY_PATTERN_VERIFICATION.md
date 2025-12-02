# 실무 권장 패턴 반영 검증 보고서

## 실무 권장 패턴 가치관

### 핵심 원칙
> **"Retry는 절대 공짜가 아니다. 스레드 점유 비용을 충분히 고려해야 한다."**

### 권장 패턴
1. **중요도가 낮은 API → Retry를 쓰지 않음**
2. **중요도가 높은 API → Retry하되:**
   - Backoff 적용
   - 최대 횟수 엄격히 제한
   - **가능하면 유저 요청이 아닌 배치/스케줄러로 Retry 이동**

### 예시
- ❌ **유저 요청(실시간 API)에서 Retry → 위험**
- ✅ **주문 상태 동기화(비동기 배치)에서 Retry → 안전**

---

## 현재 구현 상태 분석

### 1. ⚠️ 결제 요청 API: 유저 요청 경로에서 Retry 적용

**위치**: `PaymentGatewayClient.requestPayment()` (FeignClient 레벨)

**현재 동작**:
- 유저 요청 경로: `POST /api/v1/orders` → `PurchasingFacade.createOrder()` → `requestPaymentToGateway()` → `PaymentGatewayClient.requestPayment()`
- **Retry가 FeignClient 레벨에서 적용됨**
- 유저 요청 스레드에서 Retry 수행 (최대 3회, Exponential Backoff)

**문제점**:
- ⚠️ 유저 요청 스레드를 점유함
- 최악의 경우: 초기 시도(6초) + 재시도1(500ms 대기 + 6초) + 재시도2(1000ms 대기 + 6초) = 약 20초 이상 스레드 점유 가능
- 동시 요청이 많을 경우 스레드 풀 고갈 위험

**검증 결과**: ⚠️ **권장 패턴과 부분적으로 일치하지 않음**
- Retry가 유저 요청 경로에 적용됨
- 스레드 점유 비용 발생

---

### 2. ✅ Backoff 적용

**위치**: `Resilience4jRetryConfig.java`

**구현 내용**:
- Exponential Backoff 적용
- 초기 대기 시간: 500ms
- 배수: 2
- 최대 대기 시간: 5초
- 랜덤 jitter 활성화

**검증 결과**: ✅ **완벽하게 구현됨**

---

### 3. ✅ 최대 횟수 엄격히 제한

**위치**: `Resilience4jRetryConfig.java`

**구현 내용**:
- 최대 재시도 횟수: 3회 (초기 시도 포함)
- 즉, 실제 재시도는 2회만 수행

**검증 결과**: ✅ **완벽하게 구현됨**
- 엄격히 제한됨 (3회)

---

### 4. ✅ 주문 상태 동기화: 배치/스케줄러에서 처리

**위치**: `PaymentRecoveryScheduler.recoverPendingOrders()`

**현재 동작**:
- 스케줄러에서 주기적으로 실행 (1분마다)
- PENDING 상태 주문들을 조회하여 상태 복구
- `recoverOrderStatusByPaymentCheck()` 호출 시 Retry 적용됨

**검증 결과**: ✅ **권장 패턴과 일치**
- 배치/스케줄러에서 Retry 수행
- 유저 요청 스레드 점유 없음

---

## 문제점 및 개선 방안

### ⚠️ 문제: 결제 요청 API가 유저 요청 경로에서 Retry 사용

**현재 구조**:
```
유저 요청 → createOrder() → requestPaymentToGateway() → PaymentGatewayClient.requestPayment()
                                                              ↑
                                                         [Retry 적용]
```

**권장 구조**:
```
유저 요청 → createOrder() → requestPaymentToGateway() → PaymentGatewayClient.requestPayment()
                                                              ↑
                                                         [Retry 없음, 빠른 실패]
                                                         
스케줄러 → recoverPendingOrders() → recoverOrderStatusByPaymentCheck() → getTransactionsByOrder()
                                                                              ↑
                                                                         [Retry 적용]
```

### 개선 방안

#### 옵션 1: 결제 요청 API에서 Retry 제거 (권장)
- 유저 요청 경로에서는 Retry 없이 빠르게 실패
- 실패 시 주문을 PENDING 상태로 유지
- 스케줄러에서 주기적으로 상태 복구 (Retry 적용)

**장점**:
- 유저 요청 스레드 점유 최소화
- 빠른 응답 시간 보장
- 스레드 풀 고갈 방지

**단점**:
- 결제 요청이 즉시 실패할 수 있음 (하지만 주문은 PENDING 상태로 유지되어 나중에 복구 가능)

#### 옵션 2: Retry 횟수 최소화 (현재 유지하되 개선)
- 현재 3회 → 1회로 감소 (즉, 재시도 없이 초기 시도만)
- 또는 Retry를 완전히 제거하고 타임아웃만 적용

**장점**:
- 스레드 점유 시간 감소
- 구현 변경 최소화

**단점**:
- 여전히 유저 요청 스레드에서 Retry 수행

---

## 검증 요약

| 항목 | 권장 패턴 | 현재 구현 | 평가 |
|------|---------|---------|------|
| 중요도 낮은 API Retry 없음 | ✅ 권장 | ✅ 적용 | ✅ 적절 |
| Backoff 적용 | ✅ 권장 | ✅ 적용 | ✅ 완벽 |
| 최대 횟수 엄격히 제한 | ✅ 권장 | ✅ 3회 | ✅ 완벽 |
| 배치/스케줄러로 Retry 이동 | ✅ 권장 | ⚠️ 부분적 | ⚠️ 개선 필요 |
| 유저 요청에서 Retry 최소화 | ✅ 권장 | ⚠️ 적용됨 | ⚠️ 개선 필요 |

---

## 결론

**현재 구현 상태**:
- ✅ Backoff 적용: 완벽하게 구현됨
- ✅ 최대 횟수 제한: 엄격히 제한됨 (3회)
- ✅ 배치/스케줄러에서 Retry: 주문 상태 동기화는 스케줄러에서 처리
- ⚠️ 유저 요청 경로에서 Retry: 결제 요청 API에 Retry가 적용됨

**권장 패턴 준수도**: **70%**

**개선 권장 사항**:
1. **결제 요청 API에서 Retry 제거 또는 최소화**
   - 유저 요청 경로에서는 Retry 없이 빠르게 실패
   - 실패 시 주문을 PENDING 상태로 유지
   - 스케줄러에서 주기적으로 상태 복구 (Retry 적용)

2. **Retry 적용 범위 재검토**
   - 유저 요청 경로: Retry 제거 또는 최소화 (1회)
   - 배치/스케줄러: Retry 유지 또는 강화

**핵심 가치관 반영**:
- ⚠️ "Retry는 절대 공짜가 아니다" - 유저 요청 경로에서 Retry 사용으로 스레드 점유 비용 발생
- ✅ "가능하면 유저 요청이 아닌 배치/스케줄러로 Retry 이동" - 주문 상태 동기화는 스케줄러에서 처리

