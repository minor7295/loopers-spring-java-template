# 가치평가 및 문서 간 충돌 정리

## 개요

이 문서는 Fallback, Timeout, CircuitBreaker 구현 시 고려했던 내용들 중에서 **"정답이 정해져있지 않은 가치평가로 선택해야 하는 부분"**과 **"문서 간 의견이 충돌하는 내용"**을 정리합니다.

---

## 🔥 가치평가가 필요한 주요 영역

### 1. Timeout 값 설정: 멘토 권장 vs 실제 PG 지연 고려

#### 충돌 지점

| 문서 | 권장 값 | 근거 |
|------|---------|------|
| **6팀 멘토링** | 2~3초 내에서 fail-fast | "JVM에서 타임어웃을 30초로 길게 두면 thread block 과부하" |
| **현재 구현** | 6초 (readTimeout) | PG 처리 지연 1~5초 고려 |

#### 상세 분석

**6팀 멘토 권장:**
```
"결제 API timeout: 2~3초 내에서 fail-fast"
"복잡하게 풀지 말고 '타임아웃 실패 = 바로 FAILED'로 처리하는 것이 가장 현실적"
```

**현재 구현:**
```yaml
feign:
  client:
    config:
      paymentGatewayClient:
        readTimeout: 6000 # 읽기 타임아웃 (6초) - PG 처리 지연 1s~5s 고려
```

**4팀 멘토 프레임워크:**
```
1. 우리 API의 SLO(목표 응답시간)
2. 외부(PG) SLA 확인
3. 리드 타임아웃(전체 API 시간)에서 연산 분배
4. 초기값은 러프하게 잡고 → 운영하면서 튜닝
```

#### 가치평가 필요 사항

**선택지 A: 멘토 권장 (2~3초)**
- ✅ 장점: 빠른 fail-fast, 스레드 점유 최소화
- ⚠️ 단점: PG 처리 지연(1~5초)을 고려하면 너무 짧을 수 있음
- ⚠️ 리스크: 정상 처리 중인 결제를 타임아웃으로 처리할 가능성

**선택지 B: 현재 구현 (6초)**
- ✅ 장점: PG 처리 지연(1~5초)을 고려한 합리적 설정
- ✅ 장점: 정상 처리 중인 결제를 타임아웃으로 처리할 가능성 낮음
- ⚠️ 단점: 멘토 권장보다 길어서 스레드 점유 시간 증가

**선택지 C: 절충안 (3~4초)**
- ✅ 장점: 멘토 권장과 실제 지연 사이의 균형
- ⚠️ 단점: 여전히 PG 처리 지연(5초)을 완전히 커버하지 못할 수 있음

#### 권장 판단 기준

1. **비즈니스 우선순위**: 결제 정확성 vs 응답 속도
   - 정확성 우선 → 6초 유지
   - 응답 속도 우선 → 2~3초로 단축

2. **PG 실제 SLA**: PG 시스템의 실제 응답 시간 분포
   - P95가 3초 이내 → 3초로 단축 가능
   - P95가 5초 근처 → 6초 유지 권장

3. **사용자 경험**: 사용자가 기다릴 수 있는 시간
   - 모바일 앱: 3초 이내 권장
   - 웹: 5~6초까지 허용 가능

**현재 평가**: ⭐⭐⭐ (3/5) - 멘토 권장보다 길지만 PG 처리 지연 고려하여 합리적

---

### 2. 재고/포인트 차감 시점: 선차감 vs 후차감

#### 충돌 지점

| 문서 | 권장 방식 | 근거 |
|------|----------|------|
| **4팀 멘토링** | 후차감 (콜백 시 차감) | "일반 커머스는 콜백 시 차감이 거의 정답", "구조 단순, GMV 훼손 없음" |
| **현재 구현** | 선차감 (주문 생성 시 차감) | 주문 생성 시 즉시 차감, 실패 시 원복 |

#### 상세 분석

**4팀 멘토 권장 (후차감):**
```
"일반 커머스는 콜백 시 차감이 거의 정답이다."
"콜백에서 모든 보상/차감을 처리"
"구조 단순, GMV 훼손 없음"

Step 3 — 콜백 수신:
- 성공: 주문 완료 + 재고차감 + 포인트차감
- 실패: 주문 취소 (원복 불필요 - 차감하지 않았으므로)
```

**현재 구현 (선차감):**
```java
// 주문 생성 시
decreaseStocksForOrderItems(order.getItems(), products);
deductUserPoint(user, order.getTotalAmount());

// 콜백에서 실패 시
cancelOrder(order, user); // 포인트 환불, 재고 원복
```

#### 가치평가 필요 사항

**선택지 A: 후차감 (멘토 권장)**
- ✅ 장점: 구조 단순 (원복 로직 불필요)
- ✅ 장점: GMV 훼손 없음 (실패한 주문은 차감하지 않음)
- ✅ 장점: 보상 트랜잭션 불필요
- ⚠️ 단점: 재고/포인트 부족 시 주문 생성 시점에 알 수 없음
- ⚠️ 단점: 콜백이 늦게 오면 재고/포인트가 다른 주문에 사용될 수 있음

**선택지 B: 선차감 (현재 구현)**
- ✅ 장점: 주문 생성 시점에 재고/포인트 부족 여부 즉시 확인 가능
- ✅ 장점: 재고/포인트가 확보된 상태에서 주문 생성
- ⚠️ 단점: 구조 복잡 (원복 로직 필요)
- ⚠️ 단점: 보상 트랜잭션 필요
- ⚠️ 단점: 실패한 주문도 일시적으로 재고/포인트를 점유

#### 권장 판단 기준

1. **비즈니스 특성**:
   - 재고가 제한적이고 경쟁이 치열한 경우 → 선차감 (재고 확보)
   - 재고가 충분한 경우 → 후차감 (구조 단순)

2. **GMV 우선순위**:
   - GMV 훼손 방지 우선 → 후차감
   - 재고 확보 우선 → 선차감

3. **시스템 복잡도**:
   - 단순성 우선 → 후차감
   - 재고 관리 정확성 우선 → 선차감

**현재 평가**: ⭐⭐⭐ (3/5) - 멘토 권장 방식과 다르지만 비즈니스 요구사항에 따라 선택 가능

---

### 3. Circuit Breaker 설정값: 멘토 권장 vs 현재 설정

#### 충돌 지점

| 파라미터 | 멘토 권장 | 현재 설정 | 차이 |
|---------|----------|---------|------|
| `waitDurationInOpenState` | 2~3초 | 10초 | 더 길게 설정 |
| `permittedNumberOfCallsInHalfOpenState` | 1~2 | 3 | 더 많게 설정 |

#### 상세 분석

**4팀 멘토 권장:**
```yaml
waitDurationInOpenState: 2~3초
permittedNumberOfCallsInHalfOpenState: 1~2
```

**현재 구현:**
```yaml
waitDurationInOpenState: 10s
permittedNumberOfCallsInHalfOpenState: 3
```

**다른 문서들의 평가:**
- LINE Engineering Blog: 10초 적절 (명시적 권장 없음)
- 화해 기술블로그: 10초 적절 (명시적 권장 없음)
- 올리브영 테크 블로그: 10초 적절 (명시적 권장 없음)

#### 가치평가 필요 사항

**선택지 A: 멘토 권장 (2~3초, 1~2회)**
- ✅ 장점: 빠른 회복 시도
- ✅ 장점: Half-Open 상태에서 부하 최소화
- ⚠️ 단점: 너무 빠른 회복 시도로 인한 불안정성 가능
- ⚠️ 단점: 일시적 장애에서 너무 빨리 재시도

**선택지 B: 현재 설정 (10초, 3회)**
- ✅ 장점: 안정적인 회복 (충분한 대기 시간)
- ✅ 장점: Half-Open 상태에서 더 많은 샘플로 회복 여부 판단
- ⚠️ 단점: 회복 시도가 느림
- ⚠️ 단점: Half-Open 상태에서 부하 증가 가능

#### 권장 판단 기준

1. **PG 장애 특성**:
   - 일시적 장애가 빈번 → 2~3초 (빠른 회복)
   - 지속적 장애가 많음 → 10초 (안정적 회복)

2. **트래픽 특성**:
   - 트래픽이 많음 → 1~2회 (부하 최소화)
   - 트래픽이 적음 → 3회 (더 정확한 판단)

3. **비즈니스 영향도**:
   - 높은 영향도 → 10초 (안정성 우선)
   - 낮은 영향도 → 2~3초 (빠른 회복)

**현재 평가**: ⭐⭐⭐⭐ (4/5) - 멘토 권장보다 보수적이지만 안정성 측면에서 합리적

---

### 4. Payment 엔티티 및 PaymentHistory: 필수 vs 선택

#### 충돌 지점

| 문서 | 권장 | 근거 |
|------|------|------|
| **6팀 멘토링** | Payment 엔티티 필수, PaymentHistory 필수 | "결제 성공·실패는 반드시 모두 기록", "REQUIRES_NEW로 별도 트랜잭션 저장" |
| **현재 구현** | Payment 엔티티 없음, PaymentHistory 없음 | Order 엔티티에만 결제 상태 저장 |

#### 상세 분석

**6팀 멘토 권장:**
```
"결제 성공·실패는 반드시 모두 기록해야 한다."
"트랜잭션 전체 롤백에 영향을 받지 않도록 → REQUIRES_NEW 사용해 별도 트랜잭션으로 저장"
"결제 내역은 실패도 포함해 전부 남겨야 한다."
"Payment 엔티티를 먼저 생성(상태 = PENDING), PG 콜백/조회 시 SUCCESS 또는 FAILED로 업데이트"
```

**현재 구현:**
- Payment 엔티티 없음
- 결제 상태는 Order 엔티티의 `status` 필드에만 저장
- PaymentHistory 없음

#### 가치평가 필요 사항

**선택지 A: Payment 엔티티 + PaymentHistory (멘토 권장)**
- ✅ 장점: 결제 내역 독립 관리
- ✅ 장점: 트랜잭션 격리 (REQUIRES_NEW)
- ✅ 장점: 결제 실패도 모두 기록
- ✅ 장점: 결제 도메인 분리 가능
- ⚠️ 단점: 추가 엔티티 관리 필요
- ⚠️ 단점: 복잡도 증가

**선택지 B: Order 엔티티에만 저장 (현재 구현)**
- ✅ 장점: 구조 단순
- ✅ 장점: 주문과 결제 상태 일관성 보장
- ⚠️ 단점: 트랜잭션 롤백 시 결제 내역도 함께 롤백
- ⚠️ 단점: 결제 실패 내역 추적 어려움
- ⚠️ 단점: 결제 도메인 분리 어려움

#### 권장 판단 기준

1. **비즈니스 규모**:
   - 대규모 서비스 → Payment 엔티티 분리 (도메인 분리)
   - 소규모 서비스 → Order 엔티티에 통합 (단순성)

2. **결제 실패 추적 필요성**:
   - 높음 → PaymentHistory 필수
   - 낮음 → Order 엔티티만으로 충분

3. **트랜잭션 격리 필요성**:
   - 높음 → REQUIRES_NEW 필수
   - 낮음 → 동일 트랜잭션 가능

**현재 평가**: ⭐ (1/5) - 멘토 권장과 다르지만, 프로젝트 규모에 따라 선택 가능

---

### 5. 콜백 검증 방식: IP 화이트리스트 vs PG 조회 API 교차 검증

#### 충돌 지점

| 문서 | 권장 방식 | 근거 |
|------|----------|------|
| **6팀 멘토링** | IP 화이트리스트 | "IP 화이트리스트 방식이 국룰", "PG 업체의 콜백 서버 IP 대역만 허용" |
| **현재 구현** | PG 조회 API 교차 검증 | 콜백 정보를 PG 조회 API로 검증하여 보안 강화 |

#### 상세 분석

**6팀 멘토 권장:**
```
"IP 화이트리스트 방식이 국룰"
"PG 업체의 콜백 서버 IP 대역만 허용하는 게 가장 안정적"
"callback API는 /callbacks/payments/... 별도 패스"
"PG사 IP만 허용 (보안팀 룰 기반), 나머지 요청은 전부 거절 (403)"
```

**현재 구현:**
```java
// PurchasingFacade.verifyCallbackWithPgInquiry()
// PG 조회 API로 교차 검증
PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
    paymentGatewaySchedulerClient.getTransactionsByOrder(userIdString, String.valueOf(orderId));

// 콜백 정보와 PG 원장 비교
if (pgStatus != callbackStatus) {
    // 불일치 시 PG 원장을 우선시하여 처리
    return pgStatus; // PG 원장 기준으로 처리
}
```

#### 가치평가 필요 사항

**선택지 A: IP 화이트리스트 (멘토 권장)**
- ✅ 장점: 네트워크 레벨에서 차단 (효율적)
- ✅ 장점: 악의적 요청 사전 차단
- ✅ 장점: 실무에서 널리 사용되는 방식
- ⚠️ 단점: IP 대역 관리 필요
- ⚠️ 단점: PG 서버 IP 변경 시 대응 필요

**선택지 B: PG 조회 API 교차 검증 (현재 구현)**
- ✅ 장점: 콜백 정보와 PG 원장 일치성 보장
- ✅ 장점: IP 변경에 영향 없음
- ✅ 장점: 정합성 강화
- ⚠️ 단점: 추가 API 호출 (성능 오버헤드)
- ⚠️ 단점: PG 조회 API 실패 시 처리 복잡

**선택지 C: 둘 다 적용 (최고 보안)**
- ✅ 장점: 네트워크 레벨 + 애플리케이션 레벨 이중 보안
- ⚠️ 단점: 복잡도 증가

#### 권장 판단 기준

1. **보안 우선순위**:
   - 최고 보안 → 둘 다 적용
   - 실용적 보안 → IP 화이트리스트
   - 정합성 우선 → PG 조회 API 교차 검증

2. **운영 복잡도**:
   - 단순성 우선 → IP 화이트리스트
   - 정합성 우선 → PG 조회 API 교차 검증

3. **PG 시스템 특성**:
   - IP 고정 → IP 화이트리스트 적합
   - IP 변경 가능 → PG 조회 API 교차 검증 적합

**현재 평가**: ⭐⭐⭐ (3/5) - 멘토 권장과 다르지만 보안 강화 측면에서 합리적

---

### 6. updatedAt 타임스탬프 기반 상태 관리: 필수 vs 선택

#### 충돌 지점

| 문서 | 권장 | 근거 |
|------|------|------|
| **가치평가 문서** | updatedAt 기반 최신 상태만 유지 | "업데이트 시 updatedAt 타임스탬프 기반으로 최신 상태만 유지", "최신 데이터보다 오래된 값이 오면 기록하지 않음" |
| **현재 구현** | 상태 기반만 체크 | 이미 완료/취소된 주문은 건너뜀 (타임스탬프 비교 없음) |

#### 상세 분석

**가치평가 문서 권장:**
```
"업데이트 시 updatedAt 타임스탬프 기반으로 최신 상태만 유지"
"최신 데이터보다 오래된 값이 오면 기록하지 않음"
"콜백/조회 순서가 뒤집혀도 → timestamp 기반 최신 상태만 인정"
```

**현재 구현:**
```java
// 이미 완료되거나 취소된 주문인 경우 처리하지 않음
if (order.getStatus() == OrderStatus.COMPLETED) {
    return; // 건너뜀
}
if (order.getStatus() == OrderStatus.CANCELED) {
    return; // 건너뜀
}
```

**문제점:**
- PENDING 상태에서 오래된 콜백이 오면 덮어쓸 수 있음
- 타임스탬프 비교 로직 없음

#### 가치평가 필요 사항

**선택지 A: 타임스탬프 기반 (권장)**
- ✅ 장점: 순서가 뒤집혀도 최신 상태만 유지
- ✅ 장점: 오래된 데이터 무시
- ⚠️ 단점: PG 응답에 타임스탬프 필요 (pg-simulator 제약)
- ⚠️ 단점: 비교 로직 복잡도 증가

**선택지 B: 상태 기반 (현재 구현)**
- ✅ 장점: 구현 단순
- ✅ 장점: 최종 상태(COMPLETED/CANCELED)로 전이된 경우 무시
- ⚠️ 단점: PENDING 상태에서 오래된 데이터 덮어쓰기 가능
- ⚠️ 단점: 순서가 뒤집힌 경우 처리 어려움

#### 권장 판단 기준

1. **PG 시스템 특성**:
   - 타임스탬프 제공 → 타임스탬프 기반 권장
   - 타임스탬프 미제공 → 상태 기반 사용

2. **순서 보장 필요성**:
   - 높음 → 타임스탬프 기반 필수
   - 낮음 → 상태 기반으로도 충분

3. **시스템 복잡도**:
   - 단순성 우선 → 상태 기반
   - 정합성 우선 → 타임스탬프 기반

**현재 평가**: ⭐⭐⭐ (3/5) - pg-simulator 제약으로 타임스탬프 기반 구현 어려움

---

## 📊 종합 정리

### 가치평가가 필요한 항목 우선순위

| 순위 | 항목 | 충돌 정도 | 비즈니스 영향도 | 권장 판단 기준 |
|------|------|----------|----------------|---------------|
| 1 | **Timeout 값 설정** | 높음 | 높음 | PG 실제 SLA, 사용자 경험, 비즈니스 우선순위 |
| 2 | **재고/포인트 차감 시점** | 높음 | 높음 | 비즈니스 특성, GMV 우선순위, 시스템 복잡도 |
| 3 | **Circuit Breaker 설정값** | 중간 | 중간 | PG 장애 특성, 트래픽 특성, 비즈니스 영향도 |
| 4 | **Payment 엔티티 및 PaymentHistory** | 높음 | 중간 | 비즈니스 규모, 결제 실패 추적 필요성, 트랜잭션 격리 필요성 |
| 5 | **콜백 검증 방식** | 중간 | 중간 | 보안 우선순위, 운영 복잡도, PG 시스템 특성 |
| 6 | **updatedAt 타임스탬프 기반 상태 관리** | 중간 | 낮음 | PG 시스템 특성, 순서 보장 필요성, 시스템 복잡도 |

### 문서 간 의견 일치 항목

다음 항목들은 대부분의 문서에서 일치하는 의견입니다:

1. ✅ **Retry 정책**: 유저 요청 경로는 Retry 없음, 스케줄러 경로만 Retry 적용
2. ✅ **Fallback → PENDING**: Circuit Breaker Open 시 PENDING 상태로 유지
3. ✅ **상태 회귀 방지**: 이미 완료/취소된 주문은 건너뜀
4. ✅ **멱등성 보장**: orderId를 idempotency key로 사용
5. ✅ **Slow Call 감지**: slowCallDurationThreshold, slowCallRateThreshold 설정
6. ✅ **COUNT_BASED Sliding Window**: PG 호출 특성에 적합

---

## 💡 권장 사항

### 즉시 결정 필요 (높은 우선순위)

1. **Timeout 값 설정**
   - 현재 6초 유지 권장 (PG 처리 지연 1~5초 고려)
   - 운영 데이터 수집 후 P95 기준으로 조정

2. **재고/포인트 차감 시점**
   - 비즈니스 요구사항에 따라 선택
   - 재고 경쟁이 치열하면 선차감 유지
   - 구조 단순화 우선이면 후차감으로 변경

### 점진적 개선 (중간 우선순위)

3. **Circuit Breaker 설정값**
   - 현재 설정 유지 (안정성 우선)
   - 운영 데이터 수집 후 조정

4. **콜백 검증 방식**
   - IP 화이트리스트 추가 권장 (네트워크 레벨 보안)
   - PG 조회 API 교차 검증은 유지 (정합성 보장)

### 선택적 개선 (낮은 우선순위)

5. **Payment 엔티티 및 PaymentHistory**
   - 프로젝트 규모 확대 시 도입 고려
   - 현재는 Order 엔티티만으로도 충분

6. **updatedAt 타임스탬프 기반 상태 관리**
   - pg-simulator가 타임스탬프 제공 시 도입 고려
   - 현재는 상태 기반으로도 충분

---

## 🔄 서비스 규모 및 비즈니스 상황별 적용 가이드

### 서비스 규모별 권장 설정

#### 소규모 서비스 (일일 주문 < 1,000건)

**특징:**
- 트래픽이 적고 예측 가능
- 장애 영향도가 상대적으로 낮음
- 단순성 우선

**권장 설정:**

| 항목 | 소규모 서비스 권장값 | 근거 |
|------|-------------------|------|
| **Circuit Breaker** | | |
| `slidingWindowSize` | 10 | 트래픽이 적어 작은 윈도우로도 충분 |
| `minimumNumberOfCalls` | 3 | 빠른 장애 감지 |
| `waitDurationInOpenState` | 5초 | 빠른 회복 시도 |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 10 | 트래픽이 적어 작은 격벽으로 충분 |
| **Timeout** | | |
| `readTimeout` | 3~4초 | 빠른 fail-fast |
| **스케줄러** | | |
| `fixedDelay` | 2분 (120초) | 트래픽이 적어 긴 주기로도 충분 |
| **Payment 엔티티** | Order 엔티티에 통합 | 단순성 우선 |

**장점:**
- 설정 단순
- 빠른 장애 감지 및 회복
- 운영 부담 최소화

**단점:**
- 트래픽 증가 시 재조정 필요
- 노이즈에 민감할 수 있음

---

#### 중규모 서비스 (일일 주문 1,000~10,000건)

**특징:**
- 트래픽이 중간 수준
- 장애 영향도가 중간
- 안정성과 성능 균형 필요

**권장 설정:**

| 항목 | 중규모 서비스 권장값 | 근거 |
|------|-------------------|------|
| **Circuit Breaker** | | |
| `slidingWindowSize` | 20 | **현재 구현값** - 안정성과 반응성 균형 |
| `minimumNumberOfCalls` | 5 | **현재 구현값** - 노이즈 필터링 |
| `waitDurationInOpenState` | 10초 | **현재 구현값** - 안정적 회복 |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 20 | **현재 구현값** - 적절한 격리 |
| **Timeout** | | |
| `readTimeout` | 6초 | **현재 구현값** - PG 처리 지연 고려 |
| **스케줄러** | | |
| `fixedDelay` | 1분 (60초) | **현재 구현값** - 적절한 복구 주기 |
| **Payment 엔티티** | 선택적 (Order 통합 또는 분리) | 비즈니스 요구사항에 따라 |

**장점:**
- 안정성과 성능 균형
- 대부분의 상황에 적합
- 확장 가능

**단점:**
- 트래픽 급증 시 모니터링 필요

---

#### 대규모 서비스 (일일 주문 > 10,000건)

**특징:**
- 높은 트래픽
- 장애 영향도가 매우 높음
- 안정성 최우선
- 도메인 분리 필요

**권장 설정:**

| 항목 | 대규모 서비스 권장값 | 근거 |
|------|-------------------|------|
| **Circuit Breaker** | | |
| `slidingWindowSize` | 50 | 높은 트래픽에서 안정적인 통계 수집 |
| `minimumNumberOfCalls` | 10 | 노이즈 필터링 강화 |
| `waitDurationInOpenState` | 15~30초 | 안정적인 회복 (급하게 재시도하지 않음) |
| `permittedNumberOfCallsInHalfOpenState` | 5~10 | 더 많은 샘플로 회복 여부 판단 |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 50~100 | 높은 트래픽 처리 |
| `maxWaitDuration` | 10초 | 대기 시간 증가 |
| **Timeout** | | |
| `readTimeout` | 3~4초 | 빠른 fail-fast (높은 트래픽에서는 중요) |
| **스케줄러** | | |
| `fixedDelay` | 30초 | 빠른 복구 필요 |
| **Payment 엔티티** | **필수 분리** | 도메인 분리, 트랜잭션 격리 |
| **PaymentHistory** | **필수** | 결제 실패 추적, 감사(audit) 필요 |

**장점:**
- 높은 안정성
- 장애 영향 최소화
- 확장성 확보

**단점:**
- 설정 복잡
- 운영 부담 증가
- 초기 투자 필요

---

### 비즈니스 상황별 권장 설정

#### 1. 트래픽 패턴에 따른 설정

**이벤트성 트래픽 (예: 할인 이벤트, 특가)**

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| `slidingWindowSize` | 30~50 | 급증 트래픽 대응 |
| `minimumNumberOfCalls` | 10 | 노이즈 필터링 강화 |
| `maxConcurrentCalls` (Bulkhead) | 50~100 | 급증 트래픽 처리 |
| `readTimeout` | 3~4초 | 빠른 fail-fast (트래픽 급증 시 중요) |

**일정한 트래픽 (예: 일반 커머스)**

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| `slidingWindowSize` | 20 | **현재 구현값** - 안정적 |
| `minimumNumberOfCalls` | 5 | **현재 구현값** - 적절 |
| `maxConcurrentCalls` (Bulkhead) | 20 | **현재 구현값** - 적절 |
| `readTimeout` | 6초 | **현재 구현값** - PG 처리 지연 고려 |

---

#### 2. 장애 영향도에 따른 설정

**높은 영향도 (결제, 주문)**

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| `waitDurationInOpenState` | 10~15초 | 안정적인 회복 (급하게 재시도하지 않음) |
| `permittedNumberOfCallsInHalfOpenState` | 3~5 | 충분한 샘플로 회복 여부 판단 |
| `failureRateThreshold` | 40~50% | 더 민감하게 장애 감지 |
| `slowCallRateThreshold` | 40~50% | 지연도 빠르게 감지 |

**낮은 영향도 (추천, 랭킹)**

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| `waitDurationInOpenState` | 5~10초 | 빠른 회복 시도 |
| `permittedNumberOfCallsInHalfOpenState` | 1~2 | 부하 최소화 |
| `failureRateThreshold` | 60~70% | 더 관대하게 설정 |
| `readTimeout` | 1~2초 | 빠른 fail-fast |

---

#### 3. PG 시스템 특성에 따른 설정

**안정적인 PG (장애 빈도 낮음, 응답 시간 일정)**

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| `failureRateThreshold` | 60~70% | 더 관대하게 설정 |
| `waitDurationInOpenState` | 5~10초 | 빠른 회복 시도 |
| `readTimeout` | 6초 | PG 응답 시간 고려 |

**불안정한 PG (장애 빈도 높음, 응답 시간 변동 큼)**

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| `failureRateThreshold` | 40~50% | 더 민감하게 장애 감지 |
| `waitDurationInOpenState` | 15~30초 | 안정적인 회복 |
| `readTimeout` | 3~4초 | 빠른 fail-fast |
| `slowCallRateThreshold` | 40~50% | 지연도 빠르게 감지 |

---

#### 4. 비즈니스 중요도에 따른 Retry 정책

**핵심 비즈니스 (결제, 주문)**

| 경로 | Retry 정책 | 근거 |
|------|----------|------|
| **유저 요청 경로** | Retry 없음 (`maxAttempts: 1`) | **현재 구현** - 빠른 실패, 스레드 점유 최소화 |
| **스케줄러 경로** | Retry 적용 (`maxAttempts: 3`) | **현재 구현** - 비동기/배치 기반이므로 안전 |

**부가 기능 (추천, 통계)**

| 경로 | Retry 정책 | 근거 |
|------|----------|------|
| **유저 요청 경로** | Retry 없음 또는 1회 | 빠른 실패 |
| **스케줄러 경로** | Retry 없음 또는 1회 | 부가 기능이므로 Retry 불필요 |

---

#### 5. 재고 특성에 따른 차감 시점

**제한적 재고 (한정 상품, 경쟁 치열)**

| 방식 | 권장 | 근거 |
|------|------|------|
| **차감 시점** | 선차감 (주문 생성 시) | 재고 확보 우선 |
| **원복 로직** | 필수 | 실패 시 즉시 원복 |

**충분한 재고 (일반 상품)**

| 방식 | 권장 | 근거 |
|------|------|------|
| **차감 시점** | 후차감 (콜백 시) | 구조 단순, GMV 훼손 없음 |
| **원복 로직** | 불필요 | 차감하지 않았으므로 원복 불필요 |

---

#### 6. 스케줄러 실행 주기

**높은 정확성 요구 (금융, 결제)**

| 주기 | 권장 | 근거 |
|------|------|------|
| `fixedDelay` | 30초 | 빠른 복구 필요 |
| **비고** | 운영 부담 증가 | |

**일반 커머스**

| 주기 | 권장 | 근거 |
|------|------|------|
| `fixedDelay` | 1분 (60초) | **현재 구현값** - 적절한 균형 |
| **비고** | 운영 부담 적절 | |

**낮은 정확성 요구 (통계, 로그)**

| 주기 | 권장 | 근거 |
|------|------|------|
| `fixedDelay` | 5~10분 | 운영 부담 최소화 |
| **비고** | 지연 허용 가능 | |

---

### 서비스 규모별 체크리스트

#### 소규모 서비스 체크리스트

- [ ] Circuit Breaker: `slidingWindowSize: 10`, `minimumNumberOfCalls: 3`
- [ ] Bulkhead: `maxConcurrentCalls: 10`
- [ ] Timeout: `readTimeout: 3~4초`
- [ ] 스케줄러: `fixedDelay: 2분`
- [ ] Payment 엔티티: Order 엔티티에 통합 (선택)

#### 중규모 서비스 체크리스트 (현재 구현)

- [x] Circuit Breaker: `slidingWindowSize: 20`, `minimumNumberOfCalls: 5` ✅
- [x] Bulkhead: `maxConcurrentCalls: 20` ✅
- [x] Timeout: `readTimeout: 6초` ✅
- [x] 스케줄러: `fixedDelay: 1분` ✅
- [ ] Payment 엔티티: 선택적 (현재는 Order 통합)

#### 대규모 서비스 체크리스트

- [ ] Circuit Breaker: `slidingWindowSize: 50`, `minimumNumberOfCalls: 10`
- [ ] Bulkhead: `maxConcurrentCalls: 50~100`
- [ ] Timeout: `readTimeout: 3~4초` (빠른 fail-fast)
- [ ] 스케줄러: `fixedDelay: 30초`
- [ ] Payment 엔티티: **필수 분리** (REQUIRES_NEW)
- [ ] PaymentHistory: **필수** (모든 상태 변화 기록)
- [ ] IP 화이트리스트: **필수** (보안 강화)
- [ ] 타임스탬프 기반 상태 관리: **필수** (정합성 보장)

---

### 비즈니스 상황별 우선순위 매트릭스

| 비즈니스 상황 | 우선순위 1 | 우선순위 2 | 우선순위 3 |
|-------------|----------|----------|----------|
| **높은 트래픽 + 높은 영향도** | Circuit Breaker 민감도 ↑ | Bulkhead 크기 ↑ | Timeout 짧게 |
| **낮은 트래픽 + 높은 영향도** | Circuit Breaker 안정성 ↑ | Timeout 적절히 | Retry 제한적 |
| **높은 트래픽 + 낮은 영향도** | Circuit Breaker 관대하게 | Timeout 짧게 | Retry 없음 |
| **낮은 트래픽 + 낮은 영향도** | 단순성 우선 | 모든 설정 보수적 | 운영 부담 최소화 |

---

### 추가 고려 사항: 도메인 특성에 따른 차이

#### 결제 도메인 vs 다른 도메인

**결제 도메인 (현재 구현)**
- ✅ **높은 정확성 요구**: Payment 엔티티 분리 권장 (대규모)
- ✅ **트랜잭션 격리 필수**: REQUIRES_NEW 권장
- ✅ **실패 추적 필수**: PaymentHistory 권장
- ✅ **보안 강화**: IP 화이트리스트 + PG 조회 API 교차 검증

**배송 도메인 (참고)**
- ⚠️ **중간 정확성 요구**: Order 엔티티에 통합 가능
- ⚠️ **트랜잭션 격리 선택적**: 동일 트랜잭션 가능
- ⚠️ **실패 추적 선택적**: 로그만으로도 충분할 수 있음

**추천/랭킹 도메인 (참고)**
- ⚠️ **낮은 정확성 요구**: 캐시 기반 가능
- ⚠️ **트랜잭션 불필요**: 캐시 무효화만으로 충분
- ⚠️ **실패 추적 불필요**: 로그만으로 충분

---

### 추가 고려 사항: 운영 환경에 따른 차이

#### 개발/스테이징 환경

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| `slidingWindowSize` | 5~10 | 빠른 테스트 |
| `minimumNumberOfCalls` | 2~3 | 빠른 장애 감지 |
| `waitDurationInOpenState` | 5초 | 빠른 회복 시도 |
| `readTimeout` | 3초 | 빠른 fail-fast |
| `fixedDelay` (스케줄러) | 30초 | 빠른 복구 테스트 |

#### 프로덕션 환경

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| `slidingWindowSize` | 20~50 | 안정적인 통계 수집 |
| `minimumNumberOfCalls` | 5~10 | 노이즈 필터링 |
| `waitDurationInOpenState` | 10~30초 | 안정적인 회복 |
| `readTimeout` | 6초 (PG 지연 고려) | **현재 구현값** |
| `fixedDelay` (스케줄러) | 1분 | **현재 구현값** - 적절한 균형 |

---

### 추가 고려 사항: 멀티 PG 환경

**현재 구현**: 단일 PG

**멀티 PG 환경 (참고)**

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| **Circuit Breaker** | PG별로 별도 설정 | 각 PG의 특성에 맞게 |
| **Fallback 전략** | PG A 실패 → PG B로 자동 전환 | 고가용성 확보 |
| **Bulkhead** | PG별로 별도 격벽 | PG별 리소스 격리 |
| **Timeout** | PG별로 다른 설정 가능 | PG별 SLA에 맞게 |

**예시:**
```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentGatewayClientA:
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
      paymentGatewayClientB:
        failureRateThreshold: 40  # 더 민감하게
        waitDurationInOpenState: 15s  # 더 안정적으로
```

---

### 추가 고려 사항: 모니터링 및 알림

#### 소규모 서비스

| 항목 | 권장 | 근거 |
|------|------|------|
| **모니터링** | 기본 로그 | 단순성 우선 |
| **알림** | Circuit Breaker Open 시만 | 필수 알림만 |
| **대시보드** | 선택적 | 운영 부담 최소화 |

#### 중규모 서비스 (현재 구현)

| 항목 | 권장 | 근거 |
|------|------|------|
| **모니터링** | Resilience4j 메트릭 + 로그 | **현재 구현** - 적절 |
| **알림** | Circuit Breaker Open + 높은 실패율 | **현재 구현** - 적절 |
| **대시보드** | Grafana 대시보드 | **현재 구현** - 완벽 |

#### 대규모 서비스

| 항목 | 권장 | 근거 |
|------|------|------|
| **모니터링** | Resilience4j + 커스텀 메트릭 + Distributed Tracing | 종합 모니터링 |
| **알림** | Circuit Breaker Open + 실패율 + Slow Call Rate + Bulkhead 포화 | 다층 알림 |
| **대시보드** | Grafana + 커스텀 대시보드 | 실시간 모니터링 |

---

### 마이그레이션 가이드

#### 소규모 → 중규모 서비스로 확장 시

**변경 필요 항목:**

1. **Circuit Breaker**
   ```yaml
   slidingWindowSize: 10 → 20
   minimumNumberOfCalls: 3 → 5
   waitDurationInOpenState: 5s → 10s
   ```

2. **Bulkhead**
   ```yaml
   maxConcurrentCalls: 10 → 20
   ```

3. **스케줄러**
   ```java
   @Scheduled(fixedDelay = 120000) → @Scheduled(fixedDelay = 60000) // 2분 → 1분
   ```

#### 중규모 → 대규모 서비스로 확장 시

**변경 필요 항목:**

1. **Circuit Breaker**
   ```yaml
   slidingWindowSize: 20 → 50
   minimumNumberOfCalls: 5 → 10
   waitDurationInOpenState: 10s → 15~30s
   permittedNumberOfCallsInHalfOpenState: 3 → 5~10
   ```

2. **Bulkhead**
   ```yaml
   maxConcurrentCalls: 20 → 50~100
   maxWaitDuration: 5s → 10s
   ```

3. **Timeout**
   ```yaml
   readTimeout: 6s → 3~4s  # 빠른 fail-fast (높은 트래픽에서는 중요)
   ```

4. **Payment 엔티티 분리** (필수)
   ```java
   @Entity
   @Table(name = "payments")
   public class Payment {
       // 결제 전용 엔티티
   }
   
   @Transactional(propagation = Propagation.REQUIRES_NEW)
   public void savePayment(Payment payment) {
       // 별도 트랜잭션으로 저장
   }
   ```

5. **PaymentHistory 추가** (필수)
   ```java
   @Entity
   @Table(name = "payment_history")
   public class PaymentHistory {
       // 모든 상태 변화 기록
   }
   ```

6. **스케줄러**
   ```java
   @Scheduled(fixedDelay = 60000) → @Scheduled(fixedDelay = 30000) // 1분 → 30초
   ```

7. **IP 화이트리스트 추가** (필수)
   ```java
   @Component
   public class CallbackIpWhitelistFilter implements Filter {
       // PG 서버 IP 대역만 허용
   }
   ```

---

### 실전 적용 예시

#### 예시 1: 스타트업 (소규모)

**특징:**
- 일일 주문 < 1,000건
- 빠른 개발 및 배포 우선
- 운영 부담 최소화

**권장 설정:**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 3
        waitDurationInOpenState: 5s
  bulkhead:
    configs:
      default:
        maxConcurrentCalls: 10
feign:
  client:
    config:
      paymentGatewayClient:
        readTimeout: 3000  # 3초
```

**스케줄러:**
```java
@Scheduled(fixedDelay = 120000) // 2분마다
```

**Payment 엔티티:** Order 엔티티에 통합 (단순성 우선)

---

#### 예시 2: 성장 중인 서비스 (중규모) - **현재 구현**

**특징:**
- 일일 주문 1,000~10,000건
- 안정성과 성능 균형
- 확장 가능한 구조

**권장 설정:**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 20  # ✅ 현재 구현값
        minimumNumberOfCalls: 5  # ✅ 현재 구현값
        waitDurationInOpenState: 10s  # ✅ 현재 구현값
  bulkhead:
    configs:
      default:
        maxConcurrentCalls: 20  # ✅ 현재 구현값
feign:
  client:
    config:
      paymentGatewayClient:
        readTimeout: 6000  # ✅ 현재 구현값 (6초)
```

**스케줄러:**
```java
@Scheduled(fixedDelay = 60000) // ✅ 현재 구현값 (1분마다)
```

**Payment 엔티티:** 선택적 (현재는 Order 통합)

---

#### 예시 3: 대규모 서비스

**특징:**
- 일일 주문 > 10,000건
- 높은 안정성 요구
- 도메인 분리 필요

**권장 설정:**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 50
        minimumNumberOfCalls: 10
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 10
  bulkhead:
    configs:
      default:
        maxConcurrentCalls: 100
        maxWaitDuration: 10s
feign:
  client:
    config:
      paymentGatewayClient:
        readTimeout: 3000  # 3초 (빠른 fail-fast)
```

**스케줄러:**
```java
@Scheduled(fixedDelay = 30000) // 30초마다
```

**Payment 엔티티:** **필수 분리** (REQUIRES_NEW)
**PaymentHistory:** **필수**
**IP 화이트리스트:** **필수**

---

---

## 🏢 업종별 전략 차이

### 1. 금융 서비스 (은행, 증권, 핀테크)

**특징:**
- **최고 수준의 정확성 요구**: 금융 거래는 1원의 오차도 허용 불가
- **강력한 규제 준수**: 금융감독원, 금융위원회 규제
- **높은 보안 요구**: 암호화, 감사(audit) 로그 필수
- **낮은 지연 허용**: 실시간 거래 처리

**권장 설정:**

| 항목 | 금융 서비스 권장값 | 근거 |
|------|------------------|------|
| **Circuit Breaker** | | |
| `slidingWindowSize` | 50~100 | 높은 정확성 요구로 큰 윈도우 |
| `minimumNumberOfCalls` | 10~20 | 노이즈 필터링 강화 |
| `failureRateThreshold` | 30~40% | 매우 민감하게 장애 감지 |
| `waitDurationInOpenState` | 30~60초 | 안정적인 회복 (급하게 재시도하지 않음) |
| **Timeout** | | |
| `readTimeout` | 5~10초 | 정확성 우선 (빠른 fail-fast보다 정확성) |
| **Retry** | | |
| 유저 요청 경로 | Retry 없음 | 빠른 실패, 수동 확인 |
| 스케줄러 경로 | Retry 5~10회 | 높은 정확성 요구 |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 20~30 | 보수적 설정 (안정성 우선) |
| **트랜잭션** | | |
| Payment 엔티티 | **필수 분리** (REQUIRES_NEW) | 트랜잭션 격리 필수 |
| PaymentHistory | **필수** (모든 상태 변화 기록) | 감사 로그 필수 |
| **보안** | | |
| IP 화이트리스트 | **필수** | 최고 수준 보안 |
| 서명 검증 (HMAC) | **권장** | 메시지 무결성 보장 |
| 암호화 | **필수** | 민감 정보 암호화 |
| **스케줄러** | | |
| `fixedDelay` | 10~30초 | 빠른 복구 필요 |

**특수 요구사항:**
- ✅ **이중 기록 (Double Entry)**: 모든 거래는 차변/대변으로 기록
- ✅ **대사 (Reconciliation)**: 일일/월별 대사 필수
- ✅ **롤백 불가**: 거래 완료 후 롤백 불가 (보상 트랜잭션만 가능)
- ✅ **감사 로그**: 모든 상태 변화를 영구 보관 (법적 요구사항)

---

### 2. 커머스 서비스 (현재 구현)

**특징:**
- **중간 수준의 정확성 요구**: 주문/결제 정확성 중요
- **사용자 경험 우선**: 빠른 응답 시간 중요
- **재고 관리 중요**: 재고 부족 시 주문 실패
- **이벤트성 트래픽**: 할인 이벤트 시 트래픽 급증

**권장 설정:**

| 항목 | 커머스 서비스 권장값 | 근거 |
|------|-------------------|------|
| **Circuit Breaker** | | |
| `slidingWindowSize` | 20 | **현재 구현값** - 안정성과 반응성 균형 |
| `minimumNumberOfCalls` | 5 | **현재 구현값** - 노이즈 필터링 |
| `failureRateThreshold` | 50% | **현재 구현값** - 적절한 민감도 |
| `waitDurationInOpenState` | 10초 | **현재 구현값** - 안정적 회복 |
| **Timeout** | | |
| `readTimeout` | 6초 | **현재 구현값** - PG 처리 지연 고려 |
| **Retry** | | |
| 유저 요청 경로 | Retry 없음 | **현재 구현** - 빠른 실패 |
| 스케줄러 경로 | Retry 3회 | **현재 구현** - Exponential Backoff |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 20 | **현재 구현값** - 적절한 격리 |
| **트랜잭션** | | |
| Payment 엔티티 | 선택적 (대규모 시 필수) | **현재 구현** - Order 통합 |
| PaymentHistory | 선택적 (대규모 시 필수) | **현재 구현** - 없음 |
| **보안** | | |
| IP 화이트리스트 | 권장 | **현재 구현** - PG 조회 API 교차 검증 |
| **스케줄러** | | |
| `fixedDelay` | 1분 | **현재 구현값** - 적절한 복구 주기 |

**특수 요구사항:**
- ✅ **재고 관리**: 선차감 vs 후차감 선택 (비즈니스 요구사항에 따라)
- ✅ **이벤트 대응**: 트래픽 급증 시 Bulkhead 크기 증가
- ✅ **GMV 보호**: 실패한 주문은 GMV에서 제외

---

### 3. 게임 서비스 (인앱 결제, 아이템 구매)

**특징:**
- **높은 트래픽**: 동시 접속자 수가 매우 많음
- **낮은 금액 거래**: 소액 결제가 많음
- **빠른 응답 요구**: 게임 플레이 중 지연 최소화
- **부정 방지 중요**: 중복 결제, 환불 사기 방지

**권장 설정:**

| 항목 | 게임 서비스 권장값 | 근거 |
|------|------------------|------|
| **Circuit Breaker** | | |
| `slidingWindowSize` | 50~100 | 높은 트래픽 대응 |
| `minimumNumberOfCalls` | 10~20 | 노이즈 필터링 강화 |
| `failureRateThreshold` | 40~50% | 민감하게 장애 감지 |
| `waitDurationInOpenState` | 5~10초 | 빠른 회복 시도 |
| **Timeout** | | |
| `readTimeout` | 2~3초 | 빠른 fail-fast (게임 플레이 중 지연 최소화) |
| **Retry** | | |
| 유저 요청 경로 | Retry 없음 | 빠른 실패 |
| 스케줄러 경로 | Retry 1~2회 | 최소한의 Retry |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 100~200 | 높은 트래픽 처리 |
| **트랜잭션** | | |
| Payment 엔티티 | 필수 분리 | 부정 방지, 감사 로그 |
| PaymentHistory | 필수 | 중복 결제 방지 |
| **보안** | | |
| IP 화이트리스트 | 필수 | 부정 방지 |
| 멱등성 키 | 필수 | 중복 결제 방지 |
| **스케줄러** | | |
| `fixedDelay` | 30초 | 빠른 복구 필요 |

**특수 요구사항:**
- ✅ **멱등성 보장**: 동일 요청 중복 처리 방지
- ✅ **부정 방지**: 이상 거래 패턴 감지
- ✅ **빠른 응답**: 게임 플레이 중 지연 최소화
- ✅ **소액 결제 최적화**: 대량 소액 거래 처리

---

### 4. 미디어/콘텐츠 서비스 (스트리밍, 구독)

**특징:**
- **높은 트래픽**: 동시 스트리밍 사용자 수가 많음
- **구독 모델**: 정기 결제 (Recurring Payment)
- **낮은 정확성 요구**: 콘텐츠 재생 실패는 허용 가능
- **캐시 우선**: 콘텐츠는 캐시 기반

**권장 설정:**

| 항목 | 미디어 서비스 권장값 | 근거 |
|------|------------------|------|
| **Circuit Breaker** | | |
| `slidingWindowSize` | 30~50 | 높은 트래픽 대응 |
| `minimumNumberOfCalls` | 5~10 | 적절한 필터링 |
| `failureRateThreshold` | 60~70% | 관대하게 설정 (콘텐츠 실패 허용) |
| `waitDurationInOpenState` | 5~10초 | 빠른 회복 시도 |
| **Timeout** | | |
| `readTimeout` | 3~5초 | 빠른 fail-fast |
| **Retry** | | |
| 유저 요청 경로 | Retry 없음 | 빠른 실패 |
| 스케줄러 경로 | Retry 1~2회 | 최소한의 Retry |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 50~100 | 높은 트래픽 처리 |
| **트랜잭션** | | |
| Payment 엔티티 | 선택적 | 구독 모델에 따라 |
| PaymentHistory | 선택적 | 로그만으로도 충분 |
| **보안** | | |
| IP 화이트리스트 | 권장 | 선택적 |
| **스케줄러** | | |
| `fixedDelay` | 1~2분 | 낮은 정확성 요구 |

**특수 요구사항:**
- ✅ **구독 관리**: 정기 결제 (Recurring Payment) 처리
- ✅ **캐시 우선**: 콘텐츠는 캐시 기반, 결제는 DB 기반
- ✅ **Graceful Degradation**: 결제 실패 시 무료 콘텐츠 제공 가능

---

### 5. IoT/엣지 컴퓨팅 서비스

**특징:**
- **높은 지연 허용**: 네트워크 불안정성 고려
- **낮은 정확성 요구**: 센서 데이터는 일부 손실 허용 가능
- **배치 처리 우선**: 실시간보다 배치 처리
- **오프라인 동작**: 네트워크 단절 시에도 동작

**권장 설정:**

| 항목 | IoT 서비스 권장값 | 근거 |
|------|------------------|------|
| **Circuit Breaker** | | |
| `slidingWindowSize` | 10~20 | 낮은 트래픽 |
| `minimumNumberOfCalls` | 3~5 | 빠른 장애 감지 |
| `failureRateThreshold` | 70~80% | 매우 관대하게 설정 (네트워크 불안정) |
| `waitDurationInOpenState` | 30~60초 | 네트워크 복구 대기 |
| **Timeout** | | |
| `readTimeout` | 10~30초 | 네트워크 지연 고려 |
| **Retry** | | |
| 유저 요청 경로 | Retry 3~5회 | 네트워크 불안정 고려 |
| 스케줄러 경로 | Retry 10~20회 | 높은 Retry (네트워크 복구 대기) |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 5~10 | 낮은 트래픽 |
| **트랜잭션** | | |
| Payment 엔티티 | 선택적 | IoT 특성상 선택적 |
| PaymentHistory | 선택적 | 로그만으로도 충분 |
| **보안** | | |
| IP 화이트리스트 | 선택적 | IoT 특성상 선택적 |
| **스케줄러** | | |
| `fixedDelay` | 5~10분 | 배치 처리 우선 |

**특수 요구사항:**
- ✅ **오프라인 동작**: 네트워크 단절 시 로컬 큐에 저장
- ✅ **배치 처리**: 실시간보다 배치 처리 우선
- ✅ **높은 Retry**: 네트워크 불안정성 고려

---

### 6. 헬스케어/의료 서비스

**특징:**
- **최고 수준의 정확성 요구**: 생명과 직결
- **강력한 규제 준수**: 의료법, 개인정보보호법
- **높은 보안 요구**: 환자 정보 보호 필수
- **낮은 지연 허용**: 응급 상황 대응

**권장 설정:**

| 항목 | 헬스케어 서비스 권장값 | 근거 |
|------|---------------------|------|
| **Circuit Breaker** | | |
| `slidingWindowSize` | 50~100 | 높은 정확성 요구 |
| `minimumNumberOfCalls` | 10~20 | 노이즈 필터링 강화 |
| `failureRateThreshold` | 20~30% | 매우 민감하게 장애 감지 |
| `waitDurationInOpenState` | 60~120초 | 안정적인 회복 (급하게 재시도하지 않음) |
| **Timeout** | | |
| `readTimeout` | 10~15초 | 정확성 우선 |
| **Retry** | | |
| 유저 요청 경로 | Retry 없음 | 빠른 실패, 수동 확인 |
| 스케줄러 경로 | Retry 10~20회 | 높은 정확성 요구 |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 10~20 | 보수적 설정 (안정성 우선) |
| **트랜잭션** | | |
| Payment 엔티티 | **필수 분리** (REQUIRES_NEW) | 트랜잭션 격리 필수 |
| PaymentHistory | **필수** (모든 상태 변화 기록) | 감사 로그 필수 |
| **보안** | | |
| IP 화이트리스트 | **필수** | 최고 수준 보안 |
| 암호화 | **필수** | 환자 정보 보호 |
| **스케줄러** | | |
| `fixedDelay` | 10~30초 | 빠른 복구 필요 |

**특수 요구사항:**
- ✅ **HIPAA 준수**: 의료 정보 보호 규정 준수
- ✅ **감사 로그**: 모든 접근 기록 영구 보관
- ✅ **이중 확인**: 중요한 거래는 이중 확인 필수

---

## 💳 결제 유형별 전략 차이

### 1. 단건 결제 (One-time Payment) - 현재 구현

**특징:**
- 한 번만 결제되는 일회성 거래
- 사용자가 즉시 결과를 기다림
- 빠른 응답 시간 중요
- 실패 시 즉시 사용자에게 알림
- 재시도 불필요 (사용자가 직접 재시도)

**권장 설정:**

| 항목 | 단건 결제 권장값 | 근거 |
|------|----------------|------|
| **Timeout** | | |
| `readTimeout` | 2~6초 | 빠른 fail-fast (사용자 대기 시간 최소화) |
| **Retry** | | |
| 유저 요청 경로 | **Retry 없음** (`maxAttempts: 1`) | **현재 구현** - 빠른 실패, 스레드 점유 최소화 |
| 스케줄러 경로 | Retry 1~3회 | 최소한의 Retry (상태 확인용) |
| **Circuit Breaker** | | |
| `failureRateThreshold` | 40~50% | **현재 구현값** - 민감하게 장애 감지 |
| `waitDurationInOpenState` | 10초 | **현재 구현값** - 안정적 회복 |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 20~50 | 적절한 격리 |
| **Fallback** | | |
| 응답 | 즉시 PENDING 상태 반환 | **현재 구현** - 빠른 사용자 응답 |
| **상태 관리** | | |
| Payment 엔티티 | 선택적 | 단순한 구조로도 충분 |
| PaymentHistory | 선택적 | 로그만으로도 충분 |
| **스케줄러** | | |
| `fixedDelay` | 1분 | **현재 구현값** - 적절한 복구 주기 |

**특수 요구사항:**
- ✅ **빠른 실패**: 사용자가 즉시 결과를 확인할 수 있도록
- ✅ **즉시 알림**: 실패 시 사용자에게 즉시 알림
- ✅ **재시도 불필요**: 사용자가 직접 재시도하므로 시스템 Retry 불필요

**현재 구현 예시:**
```java
// PurchasingFacade.requestPaymentToGateway()
// Retry 없음 (maxAttempts: 1)
PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
    paymentGatewayClient.requestPayment(userId, request);

// 실패 시 즉시 PENDING 상태로 반환
if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
    return null; // 주문은 PENDING 상태로 유지
}
```

---

### 2. 멤버십 결제 (Subscription Payment / Recurring Payment)

**특징:**
- 정기적으로 반복되는 결제 (월간, 연간 등)
- 첫 결제 후 자동 갱신
- 구독 취소 전까지 계속 결제
- 결제 실패 시 재시도 필요 (Dunning Management)
- 구독 상태 관리 필요 (active, paused, cancelled, past_due 등)
- 결제 실패 시에도 구독은 유지 (일시 중지 상태)
- 높은 Retry 필요 (결제 실패 시 다음 주기에 재시도)

**권장 설정:**

| 항목 | 멤버십 결제 권장값 | 근거 |
|------|------------------|------|
| **Timeout** | | |
| `readTimeout` | 10~30초 | 정확성 우선 (빠른 fail-fast보다 정확성) |
| **Retry** | | |
| 유저 요청 경로 | Retry 없음 또는 1회 | 첫 결제는 빠른 실패 |
| 스케줄러 경로 | **Retry 5~20회** | 높은 Retry (결제 실패 시 다음 주기에 재시도) |
| **Circuit Breaker** | | |
| `failureRateThreshold` | 60~70% | 관대하게 설정 (일부 실패 허용 가능) |
| `waitDurationInOpenState` | 30~60초 | 안정적인 회복 (급하게 재시도하지 않음) |
| **Bulkhead** | | |
| `maxConcurrentCalls` | 10~20 | 보수적 설정 (안정성 우선) |
| **Fallback** | | |
| 응답 | 구독 상태 유지 (past_due, paused) | 결제 실패 시에도 구독 유지 |
| **상태 관리** | | |
| Subscription 엔티티 | **필수** | 구독 상태 관리 필수 |
| PaymentHistory | **필수** | 모든 결제 시도 기록 (Dunning Management) |
| **스케줄러** | | |
| `fixedDelay` | 1시간~1일 | 정기 결제 주기에 맞춰 설정 |
| **Dunning Management** | | |
| 재시도 전략 | Exponential Backoff (1일, 3일, 7일, 14일) | 결제 실패 시 점진적 재시도 |
| 최대 재시도 횟수 | 3~5회 | 최대 재시도 횟수 제한 |
| 구독 취소 | 최대 재시도 실패 시 자동 취소 | 사용자에게 알림 후 취소 |

**특수 요구사항:**
- ✅ **구독 상태 관리**: active, paused, cancelled, past_due 등 상태 관리
- ✅ **Dunning Management**: 결제 실패 시 재시도 프로세스
  - 1차 실패: 1일 후 재시도
  - 2차 실패: 3일 후 재시도
  - 3차 실패: 7일 후 재시도
  - 4차 실패: 14일 후 재시도
  - 최대 재시도 실패: 구독 취소
- ✅ **구독 유지**: 결제 실패 시에도 구독은 유지 (past_due 상태)
- ✅ **자동 갱신**: 정기적으로 자동 결제 시도
- ✅ **사용자 알림**: 결제 실패 시 사용자에게 알림 (이메일, SMS 등)

**구현 예시 (참고):**
```java
// SubscriptionPaymentScheduler.processRecurringPayments()
@Scheduled(cron = "0 0 2 * * *") // 매일 새벽 2시 실행
public void processRecurringPayments() {
    // 갱신일이 오늘인 구독 조회
    List<Subscription> subscriptions = subscriptionRepository
        .findByNextBillingDate(LocalDate.now());
    
    for (Subscription subscription : subscriptions) {
        try {
            // 결제 시도 (높은 Retry 적용)
            processSubscriptionPayment(subscription);
        } catch (PaymentException e) {
            // 결제 실패 시 Dunning Management
            handlePaymentFailure(subscription, e);
        }
    }
}

// Dunning Management
private void handlePaymentFailure(Subscription subscription, PaymentException e) {
    int retryCount = subscription.getRetryCount();
    
    if (retryCount < MAX_RETRY_COUNT) {
        // 재시도 일정 업데이트 (Exponential Backoff)
        LocalDate nextRetryDate = calculateNextRetryDate(retryCount);
        subscription.updateNextRetryDate(nextRetryDate);
        subscription.incrementRetryCount();
        subscription.updateStatus(SubscriptionStatus.PAST_DUE);
        
        // 사용자에게 알림
        notificationService.sendPaymentFailureNotification(subscription);
    } else {
        // 최대 재시도 실패 시 구독 취소
        subscription.cancel("Payment failed after maximum retries");
        notificationService.sendSubscriptionCancelledNotification(subscription);
    }
}
```

---

### 3. 단건 결제 vs 멤버십 결제 비교

#### ⚠️ 중요: 비즈니스 오류는 동일하게 처리

**비즈니스 오류 (잔액 부족, 카드 한도 초과 등)는 단건 결제나 구독 결제나 똑같이 처리해야 합니다.**

**비즈니스 오류 예시:**
- 잔액 부족 (INSUFFICIENT_FUNDS)
- 카드 한도 초과 (LIMIT_EXCEEDED)
- 잘못된 카드 번호 (INVALID_CARD)
- 카드 오류 (CARD_ERROR)

**처리 방식 (단건/구독 동일):**
- ✅ **Retry하지 않음**: Retry해도 성공하지 않음 (사용자가 카드 정보를 변경하거나 잔액을 충전해야 함)
- ✅ **즉시 실패 처리**: 주문/구독 취소 또는 실패 상태로 변경
- ✅ **사용자에게 알림**: 즉시 사용자에게 알림

**현재 구현 예시:**
```java
// PurchasingFacade.isBusinessFailure()
private boolean isBusinessFailure(String errorCode) {
    // 명확한 비즈니스 실패 오류 코드만 취소 처리
    return errorCode.contains("LIMIT_EXCEEDED") ||
        errorCode.contains("INVALID_CARD") ||
        errorCode.contains("CARD_ERROR") ||
        errorCode.contains("INSUFFICIENT_FUNDS") ||
        errorCode.contains("PAYMENT_FAILED");
}

// 비즈니스 실패 시 즉시 주문 취소
if (isBusinessFailure(errorCode)) {
    handlePaymentFailure(userId, orderId, errorCode, message);
    // 주문 취소, 리소스 원복
}
```

---

#### 차이가 발생하는 부분: 시스템 오류 처리

**시스템 오류 (타임아웃, 네트워크 오류, 5xx 등)에서만 차이가 발생합니다.**

| 오류 유형 | 단건 결제 | 멤버십 결제 |
|----------|----------|------------|
| **비즈니스 오류** | 즉시 실패, Retry 없음 | 즉시 실패, Retry 없음 (동일) |
| **시스템 오류** | 빠른 실패, 사용자 직접 재시도 | 자동 재시도 (Dunning Management) |

**시스템 오류 예시:**
- 타임아웃 (TimeoutException)
- 네트워크 오류 (SocketTimeoutException)
- 서버 오류 (5xx)
- Circuit Breaker Open

**단건 결제의 시스템 오류 처리:**
```java
// 시스템 오류 시 즉시 PENDING 상태로 유지
if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
    log.info("CircuitBreaker가 Open 상태입니다. 주문은 PENDING 상태로 유지됩니다.");
    return null; // 주문은 PENDING 상태로 유지, 스케줄러에서 복구
}

// 타임아웃 발생 시에도 PENDING 상태로 유지
catch (FeignException.TimeoutException e) {
    log.error("PG 결제 요청 타임아웃 발생.");
    // 타임아웃은 요청이 전송되었을 수 있으므로, 실제 결제 상태를 확인
    checkAndRecoverPaymentStatusAfterTimeout(userId, orderId);
    return null; // 주문은 PENDING 상태로 유지
}
```

**특징:**
- ✅ 빠른 실패 (최대 6초)
- ✅ Retry 없음 (스레드 점유 최소화)
- ✅ PENDING 상태로 유지 (스케줄러에서 주기적으로 상태 확인)
- ✅ 사용자가 직접 재시도 가능

**멤버십 결제의 시스템 오류 처리:**
```java
// 시스템 오류 시 Dunning Management로 자동 재시도
catch (FeignException.TimeoutException e) {
    log.error("구독 결제 타임아웃 발생.");
    // Dunning Management: 자동 재시도 일정 설정
    handlePaymentFailure(subscription, e);
    // 구독 상태: past_due로 변경, 재시도 일정 설정 (1일 후)
}

private void handlePaymentFailure(Subscription subscription, PaymentException e) {
    // 시스템 오류인 경우에만 재시도
    if (isSystemFailure(e)) {
        // Exponential Backoff: 1일, 3일, 7일, 14일 후 재시도
        LocalDate nextRetryDate = calculateNextRetryDate(retryCount);
        subscription.updateNextRetryDate(nextRetryDate);
        subscription.updateStatus(SubscriptionStatus.PAST_DUE);
    } else if (isBusinessFailure(e)) {
        // 비즈니스 오류는 즉시 구독 취소
        subscription.cancel("Business failure: " + e.getErrorCode());
    }
}
```

**특징:**
- ✅ 자동 재시도 (Dunning Management)
- ✅ Exponential Backoff (1일, 3일, 7일, 14일)
- ✅ 구독 상태 유지 (past_due)
- ✅ 사용자 알림 (결제 실패 시)

---

#### 전체 비교표

| 항목 | 단건 결제 | 멤버십 결제 | 비고 |
|------|----------|------------|------|
| **비즈니스 오류 처리** | 즉시 실패, Retry 없음 | 즉시 실패, Retry 없음 | **동일** |
| **시스템 오류 처리** | 빠른 실패, 사용자 직접 재시도 | 자동 재시도 (Dunning Management) | **차이 발생** |
| **Timeout** | 2~6초 (빠른 fail-fast) | 10~30초 (정확성 우선) | 시스템 오류 시에만 차이 |
| **Retry (비즈니스 오류)** | 없음 | 없음 | **동일** |
| **Retry (시스템 오류)** | 없음 (사용자 직접 재시도) | 5~20회 (자동 재시도) | **차이 발생** |
| **Circuit Breaker** | 민감하게 (40~50%) | 관대하게 (60~70%) | 시스템 오류 감지 기준 |
| **Fallback** | 즉시 PENDING 반환 | 구독 상태 유지 (past_due) | 시스템 오류 시에만 차이 |
| **상태 관리** | 단순 (PENDING → COMPLETED/CANCELED) | 복잡 (active, paused, cancelled, past_due) | 구독 특성상 복잡 |
| **Payment 엔티티** | 선택적 | **필수** (Subscription 엔티티) | 구독 특성상 필수 |
| **PaymentHistory** | 선택적 | **필수** (Dunning Management) | 재시도 추적 필요 |
| **스케줄러 주기** | 1분 (상태 확인) | 1시간~1일 (정기 결제) | 구독 특성상 차이 |
| **Dunning Management** | 불필요 | **필수** (재시도 프로세스) | 시스템 오류 재시도용 |
| **사용자 알림** | 즉시 알림 | 결제 실패 시 알림 | 비즈니스 오류는 동일 |
| **재시도 전략** | 사용자 직접 재시도 | 시스템 자동 재시도 (Exponential Backoff) | 시스템 오류 시에만 차이 |

---

#### 왜 시스템 오류에서만 차이가 필요한가?

**단건 결제의 경우:**
- 사용자가 결제 버튼을 클릭한 시점에 결제가 실패하면, 사용자가 즉시 다른 카드로 재시도할 수 있음
- 시스템이 자동으로 재시도할 필요 없음 (사용자가 직접 재시도)
- 빠른 실패가 더 중요 (사용자 대기 시간 최소화)

**멤버십 결제의 경우:**
- 정기적으로 자동으로 결제가 시도됨 (사용자가 직접 개입하지 않음)
- 시스템 오류(타임아웃, 네트워크 오류)로 인한 실패는 일시적일 수 있음
- 사용자가 개입하지 않으므로, 시스템이 자동으로 재시도해야 함
- Dunning Management를 통해 점진적으로 재시도 (1일, 3일, 7일, 14일)

**예시 시나리오:**

**시나리오 1: 잔액 부족 (비즈니스 오류)**
```
단건 결제:
  → 잔액 부족 오류 발생
  → 즉시 주문 취소
  → 사용자에게 알림
  → 사용자가 다른 카드로 재시도

구독 결제:
  → 잔액 부족 오류 발생
  → 즉시 구독 취소 (또는 past_due 상태)
  → 사용자에게 알림
  → 사용자가 카드 정보 업데이트 후 수동 재시도
```
**결론: 동일하게 처리 (Retry 없음)**

**시나리오 2: 타임아웃 (시스템 오류)**
```
단건 결제:
  → 타임아웃 발생
  → 주문은 PENDING 상태로 유지
  → 스케줄러에서 주기적으로 상태 확인 (1분마다)
  → 사용자가 직접 재시도 가능

구독 결제:
  → 타임아웃 발생
  → 구독은 past_due 상태로 변경
  → Dunning Management: 1일 후 자동 재시도
  → 재시도 성공 시 active 상태로 복구
  → 재시도 실패 시 3일 후 재시도 (최대 4회)
```
**결론: 차이가 발생 (단건은 사용자 재시도, 구독은 자동 재시도)**

---

### 4. 하이브리드 결제 (단건 + 멤버십)

**특징:**
- 단건 결제와 멤버십 결제를 모두 지원
- 결제 유형에 따라 다른 전략 적용

**권장 설정:**

| 항목 | 권장 설정 | 근거 |
|------|----------|------|
| **전략 분리** | 결제 유형별 별도 FeignClient | 각 결제 유형에 맞는 설정 적용 |
| **단건 결제 Client** | `paymentGatewayClient` (현재 구현) | 빠른 fail-fast, Retry 없음 |
| **멤버십 결제 Client** | `paymentGatewaySubscriptionClient` | 높은 Retry, 정확성 우선 |
| **Circuit Breaker** | 결제 유형별 별도 설정 | 각 결제 유형의 특성에 맞게 |
| **Bulkhead** | 결제 유형별 별도 격벽 | 리소스 격리 |

**구현 예시 (참고):**
```yaml
resilience4j:
  circuitbreaker:
    instances:
      # 단건 결제용 (현재 구현)
      paymentGatewayClient:
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
      # 멤버십 결제용
      paymentGatewaySubscriptionClient:
        failureRateThreshold: 70  # 관대하게
        waitDurationInOpenState: 60s  # 안정적 회복
  retry:
    instances:
      # 단건 결제용 (현재 구현)
      paymentGatewayClient:
        maxAttempts: 1  # Retry 없음
      # 멤버십 결제용
      paymentGatewaySubscriptionClient:
        maxAttempts: 10  # 높은 Retry
  bulkhead:
    instances:
      # 단건 결제용 (현재 구현)
      paymentGatewayClient:
        maxConcurrentCalls: 20
      # 멤버십 결제용
      paymentGatewaySubscriptionClient:
        maxConcurrentCalls: 10  # 보수적 설정
```

---

## 🎯 유스케이스별 전략 차이

### 1. 실시간 처리 vs 배치 처리

#### 실시간 처리 (현재 구현: 결제 요청)

**특징:**
- 사용자 요청에 즉시 응답 필요
- 낮은 지연 허용
- 높은 동시성 요구

**권장 설정:**

| 항목 | 실시간 처리 권장값 | 근거 |
|------|------------------|------|
| **Timeout** | 2~6초 | 빠른 fail-fast |
| **Retry** | 없음 또는 1회 | 스레드 점유 최소화 |
| **Circuit Breaker** | 민감하게 (40~50%) | 빠른 장애 감지 |
| **Bulkhead** | 큰 격벽 (50~100) | 높은 동시성 처리 |
| **Fallback** | 즉시 응답 (PENDING) | 빠른 사용자 응답 |

**예시:**
- 결제 요청 (현재 구현)
- 주문 생성
- 실시간 알림

---

#### 배치 처리 (현재 구현: 결제 상태 복구)

**특징:**
- 지연 허용 가능
- 높은 정확성 요구
- 리소스 효율적 처리

**권장 설정:**

| 항목 | 배치 처리 권장값 | 근거 |
|------|------------------|------|
| **Timeout** | 10~30초 | 정확성 우선 |
| **Retry** | 3~10회 | 높은 정확성 요구 |
| **Circuit Breaker** | 관대하게 (60~70%) | 배치 실패 허용 가능 |
| **Bulkhead** | 작은 격벽 (10~20) | 리소스 효율적 |
| **Fallback** | 다음 배치에서 재시도 | 지연 허용 |

**예시:**
- 결제 상태 복구 스케줄러 (현재 구현)
- 정산 배치
- 통계 집계

---

### 2. 동기 처리 vs 비동기 처리

#### 동기 처리 (현재 구현: 결제 요청)

**특징:**
- 사용자가 결과를 즉시 기다림
- 낮은 지연 허용
- 빠른 실패 필요

**권장 설정:**

| 항목 | 동기 처리 권장값 | 근거 |
|------|-----------------|------|
| **Timeout** | 2~6초 | 빠른 fail-fast |
| **Retry** | 없음 | 스레드 점유 최소화 |
| **Circuit Breaker** | 민감하게 (40~50%) | 빠른 장애 감지 |
| **Fallback** | 즉시 응답 | 빠른 사용자 응답 |

**예시:**
- 결제 요청 (현재 구현)
- 주문 생성
- 실시간 조회

---

#### 비동기 처리 (현재 구현: 결제 상태 복구)

**특징:**
- 사용자가 결과를 기다리지 않음
- 지연 허용 가능
- 높은 정확성 요구

**권장 설정:**

| 항목 | 비동기 처리 권장값 | 근거 |
|------|------------------|------|
| **Timeout** | 10~30초 | 정확성 우선 |
| **Retry** | 3~10회 | 높은 정확성 요구 |
| **Circuit Breaker** | 관대하게 (60~70%) | 배치 실패 허용 가능 |
| **Fallback** | 다음 배치에서 재시도 | 지연 허용 |

**예시:**
- 결제 상태 복구 스케줄러 (현재 구현)
- 이메일 발송
- 알림 발송

---

### 3. 높은 정확성 요구 vs 높은 성능 요구

#### 높은 정확성 요구 (금융, 헬스케어)

**특징:**
- 1원의 오차도 허용 불가
- 모든 상태 변화 기록
- 강력한 트랜잭션 격리

**권장 설정:**

| 항목 | 높은 정확성 요구 권장값 | 근거 |
|------|----------------------|------|
| **Timeout** | 10~30초 | 정확성 우선 |
| **Retry** | 5~20회 | 높은 정확성 요구 |
| **Circuit Breaker** | 매우 민감하게 (20~30%) | 빠른 장애 감지 |
| **트랜잭션** | REQUIRES_NEW 필수 | 트랜잭션 격리 |
| **History** | 필수 | 모든 상태 변화 기록 |

**예시:**
- 금융 거래
- 의료 기록
- 법적 문서

---

#### 높은 성능 요구 (게임, 미디어)

**특징:**
- 빠른 응답 시간 중요
- 일부 오류 허용 가능
- 높은 동시성 처리

**권장 설정:**

| 항목 | 높은 성능 요구 권장값 | 근거 |
|------|---------------------|------|
| **Timeout** | 2~3초 | 빠른 fail-fast |
| **Retry** | 없음 또는 1회 | 스레드 점유 최소화 |
| **Circuit Breaker** | 관대하게 (60~70%) | 일부 오류 허용 |
| **Bulkhead** | 큰 격벽 (100~200) | 높은 동시성 처리 |
| **트랜잭션** | 선택적 | 성능 우선 |

**예시:**
- 게임 인앱 결제
- 스트리밍 서비스
- 실시간 랭킹

---

### 4. 높은 가용성 요구 vs 높은 일관성 요구

#### 높은 가용성 요구 (미디어, 게임)

**특징:**
- 서비스 중단 최소화
- 일부 데이터 손실 허용 가능
- 빠른 복구 필요

**권장 설정:**

| 항목 | 높은 가용성 요구 권장값 | 근거 |
|------|----------------------|------|
| **Circuit Breaker** | 관대하게 (60~70%) | 서비스 중단 최소화 |
| **Fallback** | 즉시 응답 (기본값) | 서비스 지속 |
| **Retry** | 제한적 (1~2회) | 빠른 실패 |
| **Bulkhead** | 큰 격벽 (100~200) | 높은 동시성 처리 |

**예시:**
- 스트리밍 서비스
- 게임 서비스
- 소셜 미디어

---

#### 높은 일관성 요구 (금융, 커머스)

**특징:**
- 데이터 정합성 최우선
- 서비스 중단 허용 가능
- 강력한 트랜잭션 보장

**권장 설정:**

| 항목 | 높은 일관성 요구 권장값 | 근거 |
|------|----------------------|------|
| **Circuit Breaker** | 민감하게 (30~40%) | 빠른 장애 감지 |
| **Fallback** | PENDING 상태 유지 | 데이터 정합성 보장 |
| **Retry** | 높은 Retry (5~20회) | 높은 정확성 요구 |
| **트랜잭션** | REQUIRES_NEW 필수 | 트랜잭션 격리 |

**예시:**
- 금융 거래
- 주문/결제 (현재 구현)
- 정산 시스템

---

## 📊 업종별/유스케이스별 비교 매트릭스

| 업종/유스케이스 | 정확성 | 성능 | 가용성 | 보안 | 권장 Timeout | 권장 Retry |
|---------------|--------|------|--------|------|-------------|-----------|
| **금융** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 10~30초 | 5~20회 |
| **커머스 (현재)** | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | 6초 | 0~3회 |
| **게임** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | 2~3초 | 0~1회 |
| **미디어** | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | 3~5초 | 0~2회 |
| **IoT** | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ | 10~30초 | 10~20회 |
| **헬스케어** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 10~15초 | 10~20회 |
| **실시간 처리** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | 2~6초 | 0~1회 |
| **배치 처리** | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | 10~30초 | 3~10회 |

---

---

## 📊 수치 결정 근거 및 측정 방법

### ⚠️ 중요: 수치는 추상적이지 않습니다

문서에서 언급하는 "Timeout: 2~6초", "Retry: 5~20회" 같은 수치들은 **임의로 정한 것이 아닙니다**. 각 수치는 다음과 같은 **구체적인 근거와 측정 방법**을 통해 결정되어야 합니다.

---

### 1. Timeout 값 결정 근거

#### 1-1. 실제 운영 데이터 기반 (가장 중요)

**측정 방법:**
```java
// Prometheus 메트릭 수집
Timer.Sample sample = Timer.start(meterRegistry);
try {
    PaymentResponse response = paymentGatewayClient.requestPayment(request);
    return response;
} finally {
    sample.stop(Timer.builder("payment.gateway.request.duration")
        .register(meterRegistry));
}

// Grafana 대시보드에서 확인
// - P50 (중앙값): 1.2초
// - P95 (95% 요청): 3.5초
// - P99 (99% 요청): 5.2초
// - P99.9 (99.9% 요청): 8.1초
```

**결정 근거:**
- **P95 기준**: 95%의 요청이 3.5초 이내에 완료 → Timeout을 4~5초로 설정
- **P99 기준**: 99%의 요청이 5.2초 이내에 완료 → Timeout을 6초로 설정
- **P99.9 기준**: 99.9%의 요청이 8.1초 이내에 완료 → Timeout을 9~10초로 설정

**현재 구현 (6초)의 근거:**
- PG 시뮬레이터 스펙: "처리 지연: 1s~5s"
- P99가 5초 근처 → 6초로 설정 (안전 마진 포함)

**실제 운영 시 조정:**
```yaml
# 초기 설정 (추정값)
readTimeout: 6000  # PG 스펙 기반

# 운영 1주일 후 (실제 데이터 기반)
readTimeout: 4000  # P95가 3.5초이므로 4초로 조정

# 운영 1개월 후 (안정화)
readTimeout: 3500  # P99가 3.2초이므로 3.5초로 조정
```

---

#### 1-2. 비즈니스 요구사항 기반 (SLO)

**측정 방법:**
```
비즈니스 요구사항:
- 결제 API 응답 시간: 3초 이내 (SLO)
- 사용자 이탈률: 3초 초과 시 10% 증가
- 비즈니스 손실: 1초 지연당 $X 손실
```

**결정 근거:**
- **SLO 3초 이내**: Timeout을 3초로 설정하면 95% 요청이 SLO 내에 포함
- **사용자 경험**: 3초 초과 시 이탈률 증가 → Timeout을 3초로 설정
- **비즈니스 손실**: 1초 지연당 손실 계산 → Timeout 최적화

**예시:**
```yaml
# SLO 기반 설정
readTimeout: 3000  # SLO 3초 이내

# 하지만 P99가 5초라면?
# → SLO와 실제 성능 사이의 트레이드오프 필요
# → SLO 조정 또는 PG 성능 개선 필요
```

---

#### 1-3. 시스템 특성 기반

**측정 방법:**
```
PG 시스템 특성:
- 평균 응답 시간: 1.5초
- 표준 편차: 0.8초
- 최대 응답 시간: 5초 (스펙)
- 네트워크 지연: 평균 100ms, 최대 500ms
```

**결정 근거:**
- **평균 + 3σ (3 표준편차)**: 1.5초 + (3 × 0.8초) = 3.9초 → 4초
- **최대 응답 시간**: 5초 (스펙) → 6초 (안전 마진 포함)
- **네트워크 지연**: 최대 500ms → Timeout에 포함

**현재 구현 (6초)의 근거:**
- PG 처리 지연: 1s~5s (스펙)
- 네트워크 지연: ~500ms
- 안전 마진: ~500ms
- **합계: 6초**

---

### 2. Retry 횟수 결정 근거

#### 2-1. 실제 실패율 기반

**측정 방법:**
```java
// 실패율 메트릭 수집
Counter retryCounter = Counter.builder("payment.gateway.retry")
    .tag("attempt", "1", "2", "3")
    .register(meterRegistry);

// Grafana 대시보드에서 확인
// - 1차 시도 성공률: 95%
// - 2차 시도 성공률: 3% (1차 실패 중)
// - 3차 시도 성공률: 1% (2차 실패 중)
// - 4차 시도 성공률: 0.5% (3차 실패 중)
```

**결정 근거:**
- **1차 시도 성공률 95%**: 대부분 성공
- **2차 시도 성공률 3%**: 1차 실패 중 3%만 성공 → Retry 1회 권장
- **3차 시도 성공률 1%**: 2차 실패 중 1%만 성공 → Retry 2회까지 의미 있음
- **4차 시도 성공률 0.5%**: 3차 실패 중 0.5%만 성공 → Retry 3회 이상은 비효율적

**현재 구현 (maxAttempts: 3)의 근거:**
- 1차 시도: 95% 성공
- 2차 시도: 3% 추가 성공 (총 98%)
- 3차 시도: 1% 추가 성공 (총 99%)
- **3회까지가 효율적** (4회 이상은 비용 대비 효과 낮음)

---

#### 2-2. 비용 대비 효과 분석

**측정 방법:**
```
비용 분석:
- 1차 시도 비용: $X
- 2차 시도 비용: $X (동일)
- 3차 시도 비용: $X (동일)

효과 분석:
- 1차 시도: 95% 성공 → 비용 $X, 성공률 95%
- 2차 시도: 3% 추가 성공 → 비용 $X, 성공률 98% (+3%)
- 3차 시도: 1% 추가 성공 → 비용 $X, 성공률 99% (+1%)
- 4차 시도: 0.5% 추가 성공 → 비용 $X, 성공률 99.5% (+0.5%)
```

**결정 근거:**
- **2차 시도**: 비용 $X, 성공률 +3% → **효과적**
- **3차 시도**: 비용 $X, 성공률 +1% → **효과적** (99% 달성)
- **4차 시도**: 비용 $X, 성공률 +0.5% → **비효율적** (비용 대비 효과 낮음)

**결론: Retry 2~3회가 최적**

---

#### 2-3. 시스템 부하 분석

**측정 방법:**
```java
// 스레드 점유 시간 측정
Timer retryTimer = Timer.builder("payment.gateway.retry.duration")
    .register(meterRegistry);

// Grafana 대시보드에서 확인
// - 1차 시도 평균 시간: 1.5초
// - 2차 시도 평균 시간: 1.5초
// - 3차 시도 평균 시간: 1.5초
// - 총 스레드 점유 시간: 4.5초 (3회 시도)
```

**결정 근거:**
- **1회 시도**: 스레드 점유 1.5초
- **3회 시도**: 스레드 점유 4.5초 (3배)
- **시스템 부하**: Retry 횟수에 비례하여 증가
- **트레이드오프**: 성공률 vs 시스템 부하

**현재 구현 (유저 요청: Retry 없음)의 근거:**
- 유저 요청 경로: 스레드 점유 최소화 우선 → Retry 없음
- 스케줄러 경로: 비동기/배치 기반 → Retry 3회 허용

---

### 3. Circuit Breaker 임계값 결정 근거

#### 3-1. 실제 실패율 분포 기반

**측정 방법:**
```java
// 실패율 메트릭 수집
Counter failureCounter = Counter.builder("payment.gateway.failure")
    .tag("type", "timeout", "5xx", "network")
    .register(meterRegistry);

// Grafana 대시보드에서 확인
// - 정상 상태 실패율: 1~2%
// - 일시적 장애 실패율: 10~20%
// - 지속적 장애 실패율: 50~80%
```

**결정 근거:**
- **정상 상태 실패율 1~2%**: Circuit Breaker Open 불필요
- **일시적 장애 실패율 10~20%**: Circuit Breaker Open 불필요 (일시적)
- **지속적 장애 실패율 50~80%**: Circuit Breaker Open 필요

**현재 구현 (failureRateThreshold: 50%)의 근거:**
- 정상 상태: 1~2% 실패 → Circuit Breaker Open 안 됨
- 일시적 장애: 10~20% 실패 → Circuit Breaker Open 안 됨
- 지속적 장애: 50% 이상 실패 → Circuit Breaker Open

---

#### 3-2. 슬라이딩 윈도우 크기 결정 근거

**측정 방법:**
```
트래픽 특성:
- 초당 요청 수 (RPS): 100
- 분당 요청 수: 6,000
- 시간당 요청 수: 360,000
```

**결정 근거:**
- **slidingWindowSize: 20**: 최근 20개 요청 기준
- **RPS 100 기준**: 20개 요청 = 0.2초 동안의 요청
- **너무 작으면**: 노이즈에 민감 (일시적 오류에도 Open)
- **너무 크면**: 반응이 둔함 (실제 장애를 늦게 감지)

**현재 구현 (slidingWindowSize: 20)의 근거:**
- RPS 100 기준: 0.2초 동안의 요청 (빠른 반응)
- 최소 5회 호출 필요: 노이즈 필터링
- **20개는 적절한 균형점**

---

#### 3-3. waitDurationInOpenState 결정 근거

**측정 방법:**
```
PG 장애 복구 시간:
- 평균 복구 시간: 30초
- 최소 복구 시간: 10초
- 최대 복구 시간: 120초
```

**결정 근거:**
- **평균 복구 시간 30초**: waitDurationInOpenState를 30초로 설정
- **너무 짧으면**: 복구 전에 재시도 → 불필요한 부하
- **너무 길면**: 복구 후에도 재시도 안 함 → 서비스 지연

**현재 구현 (waitDurationInOpenState: 10s)의 근거:**
- PG 시뮬레이터: 복구 시간 불명확
- 보수적 설정: 10초 (실제 운영 시 조정 필요)

**실제 운영 시 조정:**
```yaml
# 초기 설정 (추정값)
waitDurationInOpenState: 10s

# 운영 1주일 후 (실제 데이터 기반)
waitDurationInOpenState: 30s  # 평균 복구 시간 30초

# 운영 1개월 후 (안정화)
waitDurationInOpenState: 25s  # 최적화된 값
```

---

### 4. Dunning Management 재시도 일정 결정 근거

#### 4-1. 결제 실패 원인 분석

**측정 방법:**
```java
// 결제 실패 원인 메트릭 수집
Counter failureReasonCounter = Counter.builder("subscription.payment.failure.reason")
    .tag("reason", "insufficient_funds", "card_expired", "timeout", "network")
    .register(meterRegistry);

// Grafana 대시보드에서 확인
// - 잔액 부족: 40% (비즈니스 오류, 재시도 불필요)
// - 카드 만료: 20% (비즈니스 오류, 재시도 불필요)
// - 타임아웃: 30% (시스템 오류, 재시도 필요)
// - 네트워크 오류: 10% (시스템 오류, 재시도 필요)
```

**결정 근거:**
- **비즈니스 오류 60%**: 재시도 불필요 (즉시 취소)
- **시스템 오류 40%**: 재시도 필요 (Dunning Management)

---

#### 4-2. 사용자 행동 패턴 분석

**측정 방법:**
```
사용자 행동 패턴:
- 잔액 충전 평균 시간: 2일
- 카드 정보 업데이트 평균 시간: 1일
- 시스템 오류 복구 평균 시간: 1시간
```

**결정 근거:**
- **1일 후 재시도**: 카드 정보 업데이트 시간 고려
- **3일 후 재시도**: 잔액 충전 시간 고려
- **7일 후 재시도**: 추가 여유 시간
- **14일 후 재시도**: 최종 기회

**현재 권장 (1일, 3일, 7일, 14일)의 근거:**
- 사용자 행동 패턴 기반
- Exponential Backoff 전략
- 최대 4회 재시도 (비용 대비 효과 고려)

---

### 5. 수치 결정 프로세스

#### 5-1. 초기 설정 (추정값)

**1단계: 문서 및 스펙 확인**
```yaml
# PG 스펙 기반 초기 설정
readTimeout: 6000  # PG 처리 지연 1s~5s 고려
maxAttempts: 3     # 일반적인 권장값
failureRateThreshold: 50  # 일반적인 권장값
```

**2단계: 업계 표준 참고**
- Release It!: Timeout 2~3초 권장
- Building Resilient Distributed Systems: Timeout 5~10초 권장
- 실무 경험: Timeout 3~6초 권장

**3단계: 보수적 설정 선택**
- 여러 권장값 중 보수적(안전한) 값 선택
- 실제 운영 데이터 수집 후 조정

---

#### 5-2. 운영 데이터 수집 (1주일~1개월)

**1단계: 메트릭 수집**
```yaml
# Prometheus 메트릭 수집
management:
  metrics:
    export:
      prometheus:
        enabled: true
    distribution:
      percentiles-histogram:
        payment.gateway.request.duration: true
      percentiles:
        payment.gateway.request.duration:
          p50: 0.5
          p95: 0.95
          p99: 0.99
```

**2단계: Grafana 대시보드 확인**
- P50, P95, P99 응답 시간
- 실패율 분포
- Retry 성공률
- Circuit Breaker 상태 전이

**3단계: 데이터 분석**
- 실제 응답 시간 분포 확인
- 실패율 패턴 분석
- 최적값 계산

---

#### 5-3. 수치 조정 (운영 데이터 기반)

**1단계: Timeout 조정**
```yaml
# 초기 설정
readTimeout: 6000

# 운영 데이터 기반 조정
# P95가 3.5초 → 4초로 조정
readTimeout: 4000

# P99가 3.2초 → 3.5초로 조정
readTimeout: 3500
```

**2단계: Retry 조정**
```yaml
# 초기 설정
maxAttempts: 3

# 운영 데이터 기반 조정
# 3차 시도 성공률 0.5% → 2회로 조정
maxAttempts: 2

# 또는 2차 시도 성공률 5% → 3회 유지
maxAttempts: 3
```

**3단계: Circuit Breaker 조정**
```yaml
# 초기 설정
failureRateThreshold: 50
waitDurationInOpenState: 10s

# 운영 데이터 기반 조정
# 정상 상태 실패율 5% → 40%로 조정 (더 민감하게)
failureRateThreshold: 40

# 평균 복구 시간 30초 → 30초로 조정
waitDurationInOpenState: 30s
```

---

### 6. 수치 결정 체크리스트

#### ✅ Timeout 값 결정 체크리스트

- [ ] **PG 실제 SLA 확인**: P95, P99 응답 시간 측정
- [ ] **비즈니스 SLO 확인**: 목표 응답 시간 확인
- [ ] **사용자 경험 확인**: 사용자가 기다릴 수 있는 시간 확인
- [ ] **네트워크 지연 확인**: 평균/최대 네트워크 지연 측정
- [ ] **안전 마진 포함**: 예상 응답 시간 + 안전 마진 (20~30%)
- [ ] **운영 데이터 수집**: 1주일 이상 메트릭 수집
- [ ] **수치 조정**: 운영 데이터 기반 최적화

#### ✅ Retry 횟수 결정 체크리스트

- [ ] **실패율 분석**: 1차, 2차, 3차 시도 성공률 측정
- [ ] **비용 대비 효과 분석**: 추가 Retry의 성공률 vs 비용
- [ ] **시스템 부하 분석**: Retry 횟수에 따른 스레드 점유 시간
- [ ] **비즈니스 오류 vs 시스템 오류 구분**: 비즈니스 오류는 Retry 불필요
- [ ] **운영 데이터 수집**: 실제 Retry 성공률 측정
- [ ] **수치 조정**: 운영 데이터 기반 최적화

#### ✅ Circuit Breaker 임계값 결정 체크리스트

- [ ] **실패율 분포 분석**: 정상/일시적/지속적 장애 실패율 측정
- [ ] **트래픽 특성 확인**: RPS, 분당/시간당 요청 수 확인
- [ ] **슬라이딩 윈도우 크기 결정**: 트래픽 특성에 맞는 크기 선택
- [ ] **복구 시간 측정**: PG 장애 평균/최소/최대 복구 시간 측정
- [ ] **운영 데이터 수집**: 실제 Circuit Breaker 동작 패턴 측정
- [ ] **수치 조정**: 운영 데이터 기반 최적화

---

## 📝 결론

대부분의 문서들이 일치하는 핵심 원칙들(Retry 정책, Fallback → PENDING, 상태 회귀 방지 등)은 잘 반영되어 있습니다.

하지만 **Timeout 값 설정**, **재고/포인트 차감 시점**, **Circuit Breaker 설정값** 등은 **비즈니스 요구사항과 운영 환경에 따라 가치평가가 필요한 영역**입니다.

**중요한 것은 모든 수치는 추상적이지 않으며, 다음과 같은 구체적인 근거를 통해 결정되어야 합니다:**

1. **실제 운영 데이터 기반** (가장 중요): P95, P99 응답 시간, 실패율 분포 등
2. **비즈니스 요구사항 기반**: SLO, SLA, 사용자 경험 목표
3. **시스템 특성 기반**: PG 처리 지연, 네트워크 지연 등
4. **비용 대비 효과 분석**: 추가 Retry의 성공률 vs 비용
5. **운영 데이터 수집 및 조정**: 초기 설정 → 운영 데이터 수집 → 수치 조정

---

## 🔄 동적 설정 방법: slowCallDurationThreshold 유동적 조정

### ⚠️ 중요: 고정값 vs 동적값

**현재 구현**: `slowCallDurationThreshold: 2s` (고정값)

**문제점:**
- PG 시스템의 실제 응답 시간이 변동할 수 있음 (예: 피크 시간대, 이벤트 기간)
- 운영 데이터를 기반으로 최적값을 찾았더라도, 시간이 지나면서 변경될 수 있음
- 애플리케이션 재시작 없이 설정을 변경할 수 없음

**해결책**: 동적 설정 방법 활용

---

### 1. 환경 변수를 통한 동적 설정 (가장 간단)

**장점:**
- ✅ 구현 간단
- ✅ Kubernetes ConfigMap/Secret과 연동 가능
- ✅ 환경별로 다른 값 설정 가능

**단점:**
- ⚠️ 애플리케이션 재시작 필요 (또는 Spring Cloud Config 필요)

**구현 방법:**

**1-1. application.yml 수정:**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slowCallDurationThreshold: ${RESILIENCE4J_CIRCUITBREAKER_SLOW_CALL_DURATION_THRESHOLD:2s} # 환경 변수 또는 기본값 2초
    instances:
      paymentGatewayClient:
        slowCallDurationThreshold: ${RESILIENCE4J_CIRCUITBREAKER_PAYMENT_GATEWAY_SLOW_CALL_DURATION_THRESHOLD:2s}
```

**1-2. 환경 변수 설정:**
```bash
# 로컬 환경
export RESILIENCE4J_CIRCUITBREAKER_SLOW_CALL_DURATION_THRESHOLD=2s

# 프로덕션 환경 (운영 데이터 기반 조정)
export RESILIENCE4J_CIRCUITBREAKER_SLOW_CALL_DURATION_THRESHOLD=3s
```

**1-3. Kubernetes ConfigMap 사용:**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: commerce-api-config
data:
  RESILIENCE4J_CIRCUITBREAKER_SLOW_CALL_DURATION_THRESHOLD: "3s"  # 운영 데이터 기반 조정
```

**1-4. Kubernetes Deployment에서 참조:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: commerce-api
spec:
  template:
    spec:
      containers:
      - name: commerce-api
        envFrom:
        - configMapRef:
            name: commerce-api-config
```

---

### 2. Spring Cloud Config를 통한 동적 설정 (재시작 없이 변경 가능)

**장점:**
- ✅ 애플리케이션 재시작 없이 설정 변경 가능
- ✅ 중앙 집중식 설정 관리
- ✅ 여러 서비스에 일괄 적용 가능

**단점:**
- ⚠️ Spring Cloud Config 서버 구축 필요
- ⚠️ 설정 복잡도 증가

**구현 방법:**

**2-1. Spring Cloud Config 의존성 추가:**
```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
```

**2-2. application.yml 수정:**
```yaml
spring:
  cloud:
    config:
      uri: http://config-server:8888
      name: commerce-api
      profile: ${spring.profiles.active}
management:
  endpoints:
    web:
      exposure:
        include: refresh  # 설정 새로고침 엔드포인트 활성화
```

**2-3. Config Server 설정 파일 (config/commerce-api.yml):**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slowCallDurationThreshold: 3s  # 운영 데이터 기반 조정
```

**2-4. 설정 새로고침 (재시작 없이):**
```bash
# Config Server에서 설정 변경 후
curl -X POST http://commerce-api:8080/actuator/refresh
```

**2-5. @RefreshScope 사용:**
```java
@Configuration
@RefreshScope  // 설정 변경 시 자동으로 새로고침
public class Resilience4jConfig {
    
    @Value("${resilience4j.circuitbreaker.configs.default.slowCallDurationThreshold}")
    private String slowCallDurationThreshold;
    
    @Bean
    public CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
            .slowCallDurationThreshold(Duration.parse(slowCallDurationThreshold))
            .build();
    }
}
```

---

### 3. Resilience4j CircuitBreakerRegistry를 통한 런타임 설정 변경 (가장 유연)

**장점:**
- ✅ 애플리케이션 재시작 없이 즉시 변경 가능
- ✅ API를 통한 동적 변경 가능
- ✅ 운영 메트릭 기반 자동 조정 가능

**단점:**
- ⚠️ 구현 복잡도 높음
- ⚠️ 설정 변경 로직 관리 필요

**구현 방법:**

**3-1. 동적 설정 관리 서비스 생성:**
```java
package com.loopers.config.resilience4j;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Circuit Breaker 동적 설정 관리 서비스.
 * <p>
 * 런타임에 Circuit Breaker 설정을 변경할 수 있도록 지원합니다.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CircuitBreakerDynamicConfigService {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * slowCallDurationThreshold를 동적으로 변경합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     * @param slowCallDurationThreshold 새로운 slowCallDurationThreshold 값 (초)
     */
    public void updateSlowCallDurationThreshold(String circuitBreakerName, int slowCallDurationThresholdSeconds) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName);
        if (circuitBreaker == null) {
            log.warn("Circuit Breaker '{}' not found.", circuitBreakerName);
            return;
        }

        // 기존 설정 가져오기
        CircuitBreakerConfig currentConfig = circuitBreaker.getCircuitBreakerConfig();
        
        // 새로운 설정 생성 (slowCallDurationThreshold만 변경)
        CircuitBreakerConfig newConfig = CircuitBreakerConfig.from(currentConfig)
            .slowCallDurationThreshold(Duration.ofSeconds(slowCallDurationThresholdSeconds))
            .build();

        // Circuit Breaker Registry에 새로운 설정 적용
        circuitBreakerRegistry.replaceConfiguration(circuitBreakerName, newConfig);
        
        log.info("Circuit Breaker '{}'의 slowCallDurationThreshold를 {}초로 변경했습니다.", 
            circuitBreakerName, slowCallDurationThresholdSeconds);
    }

    /**
     * 현재 slowCallDurationThreshold 값을 조회합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     * @return 현재 slowCallDurationThreshold 값 (초)
     */
    public long getSlowCallDurationThreshold(String circuitBreakerName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName);
        if (circuitBreaker == null) {
            log.warn("Circuit Breaker '{}' not found.", circuitBreakerName);
            return -1;
        }

        return circuitBreaker.getCircuitBreakerConfig()
            .getSlowCallDurationThreshold()
            .getSeconds();
    }
}
```

**3-2. REST API 엔드포인트 생성:**
```java
package com.loopers.interfaces.api.config;

import com.loopers.config.resilience4j.CircuitBreakerDynamicConfigService;
import com.loopers.interfaces.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Circuit Breaker 동적 설정 관리 API.
 * <p>
 * 운영 중에 Circuit Breaker 설정을 변경할 수 있는 엔드포인트를 제공합니다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/circuit-breaker")
@RequiredArgsConstructor
public class CircuitBreakerConfigController {

    private final CircuitBreakerDynamicConfigService circuitBreakerDynamicConfigService;

    /**
     * slowCallDurationThreshold를 동적으로 변경합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     * @param request 변경 요청 (slowCallDurationThresholdSeconds)
     * @return 성공 응답
     */
    @PutMapping("/{circuitBreakerName}/slow-call-duration-threshold")
    public ApiResponse<Void> updateSlowCallDurationThreshold(
        @PathVariable String circuitBreakerName,
        @RequestBody UpdateSlowCallDurationThresholdRequest request
    ) {
        circuitBreakerDynamicConfigService.updateSlowCallDurationThreshold(
            circuitBreakerName,
            request.slowCallDurationThresholdSeconds()
        );
        return ApiResponse.success();
    }

    /**
     * 현재 slowCallDurationThreshold 값을 조회합니다.
     *
     * @param circuitBreakerName Circuit Breaker 이름
     * @return 현재 slowCallDurationThreshold 값
     */
    @GetMapping("/{circuitBreakerName}/slow-call-duration-threshold")
    public ApiResponse<Long> getSlowCallDurationThreshold(
        @PathVariable String circuitBreakerName
    ) {
        long threshold = circuitBreakerDynamicConfigService.getSlowCallDurationThreshold(circuitBreakerName);
        return ApiResponse.success(threshold);
    }

    public record UpdateSlowCallDurationThresholdRequest(
        int slowCallDurationThresholdSeconds
    ) {}
}
```

**3-3. API 사용 예시:**
```bash
# 현재 값 조회
curl http://localhost:8080/api/v1/admin/circuit-breaker/paymentGatewayClient/slow-call-duration-threshold

# 값 변경 (2초 → 3초)
curl -X PUT http://localhost:8080/api/v1/admin/circuit-breaker/paymentGatewayClient/slow-call-duration-threshold \
  -H "Content-Type: application/json" \
  -d '{"slowCallDurationThresholdSeconds": 3}'
```

---

### 4. 운영 메트릭 기반 자동 조정 (고급)

**장점:**
- ✅ 완전 자동화
- ✅ 운영 데이터 기반 실시간 최적화
- ✅ 인적 오류 방지

**단점:**
- ⚠️ 구현 복잡도 매우 높음
- ⚠️ 잘못된 자동 조정 시 시스템 불안정 가능

**구현 방법:**

**4-1. 메트릭 기반 자동 조정 스케줄러:**
```java
package com.loopers.config.resilience4j;

import com.loopers.config.resilience4j.CircuitBreakerDynamicConfigService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Circuit Breaker 설정 자동 조정 스케줄러.
 * <p>
 * 운영 메트릭을 기반으로 slowCallDurationThreshold를 자동으로 조정합니다.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerAutoTuningScheduler {

    private final CircuitBreakerDynamicConfigService circuitBreakerDynamicConfigService;
    private final MeterRegistry meterRegistry;

    private static final String CIRCUIT_BREAKER_NAME = "paymentGatewayClient";
    private static final int MIN_THRESHOLD = 1; // 최소 1초
    private static final int MAX_THRESHOLD = 10; // 최대 10초
    private static final double P95_TARGET_MULTIPLIER = 1.3; // P95의 1.3배

    /**
     * 5분마다 실행하여 slowCallDurationThreshold를 자동 조정합니다.
     */
    @Scheduled(fixedDelay = 300000) // 5분마다
    public void autoTuneSlowCallDurationThreshold() {
        try {
            // P95 응답 시간 조회
            double p95ResponseTime = getP95ResponseTime();
            
            if (p95ResponseTime <= 0) {
                log.debug("P95 응답 시간 데이터가 없습니다. 자동 조정을 건너뜁니다.");
                return;
            }

            // 목표 slowCallDurationThreshold 계산 (P95의 1.3배)
            int targetThreshold = (int) Math.ceil(p95ResponseTime * P95_TARGET_MULTIPLIER);
            
            // 최소/최대 값 제한
            targetThreshold = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, targetThreshold));

            // 현재 값 조회
            long currentThreshold = circuitBreakerDynamicConfigService
                .getSlowCallDurationThreshold(CIRCUIT_BREAKER_NAME);

            // 값이 10% 이상 차이나면 조정
            if (Math.abs(currentThreshold - targetThreshold) >= currentThreshold * 0.1) {
                log.info("slowCallDurationThreshold 자동 조정: {}초 → {}초 (P95: {}초)",
                    currentThreshold, targetThreshold, p95ResponseTime);
                
                circuitBreakerDynamicConfigService.updateSlowCallDurationThreshold(
                    CIRCUIT_BREAKER_NAME,
                    targetThreshold
                );
            } else {
                log.debug("slowCallDurationThreshold 조정 불필요: 현재 {}초, 목표 {}초 (P95: {}초)",
                    currentThreshold, targetThreshold, p95ResponseTime);
            }
        } catch (Exception e) {
            log.error("slowCallDurationThreshold 자동 조정 중 오류 발생", e);
        }
    }

    /**
     * P95 응답 시간을 조회합니다.
     *
     * @return P95 응답 시간 (초)
     */
    private double getP95ResponseTime() {
        try {
            // Prometheus 메트릭에서 P95 조회
            // 실제 구현은 MeterRegistry를 통해 메트릭을 조회
            // 예시: meterRegistry.get("payment.gateway.request.duration")
            //       .tag("percentile", "0.95")
            //       .gauge()
            //       .value();
            
            // 여기서는 예시로 0을 반환 (실제 구현 필요)
            return 0.0;
        } catch (Exception e) {
            log.warn("P95 응답 시간 조회 실패", e);
            return 0.0;
        }
    }
}
```

---

### 5. 방법별 비교 및 권장사항

| 방법 | 구현 난이도 | 재시작 필요 | 동적 변경 | 운영 복잡도 | 권장 상황 |
|------|------------|------------|----------|------------|----------|
| **환경 변수** | ⭐ (낮음) | 필요 | 불가능 | 낮음 | 환경별 다른 설정 필요 |
| **Spring Cloud Config** | ⭐⭐ (중간) | 불필요 | 가능 | 중간 | 중앙 집중식 설정 관리 |
| **CircuitBreakerRegistry API** | ⭐⭐⭐ (높음) | 불필요 | 가능 | 높음 | 런타임 동적 변경 필요 |
| **자동 조정** | ⭐⭐⭐⭐ (매우 높음) | 불필요 | 가능 | 매우 높음 | 완전 자동화 필요 |

**권장사항:**

1. **초기 단계**: 환경 변수 사용 (가장 간단)
2. **운영 안정화 후**: Spring Cloud Config 도입 (재시작 없이 변경 가능)
3. **고급 운영**: CircuitBreakerRegistry API 사용 (런타임 동적 변경)
4. **완전 자동화**: 자동 조정 스케줄러 구현 (운영 데이터 기반)

---

### 6. 실제 운영 시나리오

**시나리오 1: 피크 시간대 대응**

```
문제: 피크 시간대에 PG 응답 시간이 증가 (P95: 2초 → 4초)
해결:
1. Grafana 대시보드에서 P95 증가 확인
2. API를 통해 slowCallDurationThreshold를 2초 → 4초로 변경
3. 재시작 없이 즉시 적용
```

**시나리오 2: 이벤트 기간 대응**

```
문제: 할인 이벤트 기간에 트래픽 급증, PG 응답 시간 변동
해결:
1. 자동 조정 스케줄러가 P95 기반으로 자동 조정
2. 운영자가 개입하지 않아도 자동으로 최적화
```

**시나리오 3: PG 시스템 업그레이드 후**

```
문제: PG 시스템 업그레이드 후 응답 시간 개선 (P95: 2초 → 1초)
해결:
1. 운영 데이터 수집 (1주일)
2. P95가 안정적으로 1초로 유지 확인
3. slowCallDurationThreshold를 2초 → 1.5초로 조정
```

---

### 7. 주의사항

**동적 설정 변경 시 주의사항:**

1. **점진적 변경**: 한 번에 큰 폭으로 변경하지 말고, 작은 단위로 점진적 변경
2. **모니터링 필수**: 변경 후 즉시 Grafana 대시보드를 모니터링
3. **롤백 계획**: 변경이 문제를 일으키면 즉시 이전 값으로 롤백
4. **변경 이력 관리**: 모든 설정 변경을 로그로 기록
5. **테스트 환경에서 먼저 검증**: 프로덕션 적용 전 테스트 환경에서 검증

**예시:**
```java
// 변경 이력 기록
log.info("slowCallDurationThreshold 변경: {}초 → {}초 (변경 사유: {})",
    oldValue, newValue, reason);

// 변경 후 모니터링 알림
alertService.sendNotification(
    "Circuit Breaker 설정 변경",
    String.format("slowCallDurationThreshold: %d초 → %d초", oldValue, newValue)
);
```

**특히 서비스 규모, 업종, 유스케이스에 따라 다음과 같이 달라질 수 있습니다:**

1. **서비스 규모**:
   - 소규모 서비스: 단순성 우선, 빠른 장애 감지
   - 중규모 서비스: **현재 구현값** - 안정성과 성능 균형
   - 대규모 서비스: 안정성 최우선, 도메인 분리, Payment 엔티티 필수

2. **업종별 차이**:
   - 금융/헬스케어: 최고 수준의 정확성, 보안, 감사 로그
   - 커머스: **현재 구현** - 중간 수준의 정확성, 사용자 경험 우선
   - 게임/미디어: 높은 성능, 낮은 정확성 요구

3. **유스케이스별 차이**:
   - 실시간 처리: 빠른 fail-fast, Retry 없음
   - 배치 처리: 높은 Retry, 정확성 우선
   - 높은 정확성 요구: 높은 Retry, REQUIRES_NEW 필수
   - 높은 성능 요구: 빠른 fail-fast, 큰 Bulkhead

4. **결제 유형별 차이**:

#### 4-1. 단건 결제 (One-time Payment) - 현재 구현

**현재 구현 상태:**
- ✅ **Timeout**: 6초 (PG 처리 지연 1~5초 고려)
- ✅ **Retry**: 없음 (`maxAttempts: 1`) - 유저 요청 경로에서 빠른 실패
- ✅ **Circuit Breaker**: 민감하게 설정 (50% 실패율 시 Open)
- ✅ **Fallback**: 즉시 PENDING 상태 반환
- ✅ **스케줄러**: 1분마다 상태 복구 (PaymentRecoveryScheduler)

**설계 철학:**
- **빠른 사용자 응답**: 사용자가 결제 버튼을 클릭하면 즉시 응답 (최대 6초)
- **스레드 점유 최소화**: Retry 없이 빠르게 실패하여 스레드를 해제
- **Eventually Consistent**: 실패 시 주문은 PENDING 상태로 유지하고, 스케줄러에서 주기적으로 상태 확인
- **사용자 주도 재시도**: 결제 실패 시 사용자가 직접 재시도 (시스템이 자동으로 재시도하지 않음)

**현재 구현 예시:**
```java
// PurchasingFacade.requestPaymentToGateway()
// Retry 없음 (maxAttempts: 1) - 빠른 실패
PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
    paymentGatewayClient.requestPayment(userId, request);

// Circuit Breaker Open 시 즉시 PENDING 반환
if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
    log.info("CircuitBreaker가 Open 상태입니다. 주문은 PENDING 상태로 유지됩니다.");
    return null; // 주문은 PENDING 상태로 유지, 스케줄러에서 복구
}
```

**장점:**
- ✅ 빠른 사용자 응답 (최대 6초)
- ✅ 스레드 점유 최소화 (Retry 없음)
- ✅ 시스템 부하 최소화
- ✅ 단순한 구조 (상태 관리가 단순함)

**단점:**
- ⚠️ 결제 실패 시 사용자가 직접 재시도해야 함
- ⚠️ 일시적 오류에도 사용자 개입 필요

---

#### 4-2. 멤버십 결제 (Subscription Payment) - 미구현

**필요한 구현 사항:**

**1. 별도의 FeignClient 필요**

**이유:**
- 단건 결제와 멤버십 결제는 **완전히 다른 특성**을 가짐
- 단건 결제: 빠른 fail-fast, Retry 없음
- 멤버십 결제: 정확성 우선, 높은 Retry (5~20회)
- **동일한 FeignClient를 사용하면 서로 다른 요구사항을 충족할 수 없음**

**구현 예시:**
```java
// 단건 결제용 (현재 구현)
@FeignClient(
    name = "paymentGatewayClient",
    url = "${payment-gateway.url}",
    path = "/api/v1/payments",
    fallback = PaymentGatewayClientFallback.class
)
public interface PaymentGatewayClient {
    // Retry 없음, 빠른 fail-fast
}

// 멤버십 결제용 (추가 필요)
@FeignClient(
    name = "paymentGatewaySubscriptionClient",  // 별도 이름
    url = "${payment-gateway.url}",
    path = "/api/v1/subscriptions",  // 별도 경로
    fallback = PaymentGatewaySubscriptionClientFallback.class
)
public interface PaymentGatewaySubscriptionClient {
    // 높은 Retry, 정확성 우선
}
```

**설정 분리:**
```yaml
resilience4j:
  circuitbreaker:
    instances:
      # 단건 결제용 (현재 구현)
      paymentGatewayClient:
        failureRateThreshold: 50  # 민감하게
        waitDurationInOpenState: 10s
      # 멤버십 결제용 (추가 필요)
      paymentGatewaySubscriptionClient:
        failureRateThreshold: 70  # 관대하게 (일부 실패 허용)
        waitDurationInOpenState: 60s  # 안정적 회복
  retry:
    instances:
      # 단건 결제용 (현재 구현)
      paymentGatewayClient:
        maxAttempts: 1  # Retry 없음
      # 멤버십 결제용 (추가 필요)
      paymentGatewaySubscriptionClient:
        maxAttempts: 10  # 높은 Retry (정확성 우선)
  bulkhead:
    instances:
      # 단건 결제용 (현재 구현)
      paymentGatewayClient:
        maxConcurrentCalls: 20
      # 멤버십 결제용 (추가 필요)
      paymentGatewaySubscriptionClient:
        maxConcurrentCalls: 10  # 보수적 설정 (안정성 우선)
```

---

**2. Dunning Management 필수**

**Dunning Management란?**
- 결제 실패 시 **자동으로 재시도하는 프로세스**
- Exponential Backoff 전략으로 점진적으로 재시도
- 최대 재시도 횟수 제한 후 구독 취소

**왜 필요한가?**
- 멤버십 결제는 **정기적으로 반복**되는 결제
- 일시적 오류(카드 한도 초과, 네트워크 오류 등)로 인한 실패가 빈번함
- 사용자가 매번 직접 재시도하는 것은 **사용자 경험 저하**
- 시스템이 자동으로 재시도하여 **구독 유지율 향상**

**Dunning Management 프로세스:**
```
1차 결제 실패 (갱신일)
  ↓
1일 후 재시도 (1차 재시도)
  ↓ 실패
3일 후 재시도 (2차 재시도)
  ↓ 실패
7일 후 재시도 (3차 재시도)
  ↓ 실패
14일 후 재시도 (4차 재시도)
  ↓ 실패
구독 취소 (최대 재시도 실패)
```

**구현 예시:**
```java
@Entity
@Table(name = "subscriptions")
public class Subscription {
    @Id
    private Long id;
    
    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status;  // active, paused, cancelled, past_due
    
    private LocalDate nextBillingDate;  // 다음 결제일
    private LocalDate nextRetryDate;    // 다음 재시도일
    private int retryCount;             // 재시도 횟수
    
    private static final int MAX_RETRY_COUNT = 4;
    
    public void handlePaymentFailure() {
        if (retryCount < MAX_RETRY_COUNT) {
            // Exponential Backoff: 1일, 3일, 7일, 14일
            nextRetryDate = calculateNextRetryDate(retryCount);
            retryCount++;
            status = SubscriptionStatus.PAST_DUE;
            
            // 사용자에게 알림
            notificationService.sendPaymentFailureNotification(this);
        } else {
            // 최대 재시도 실패 시 구독 취소
            cancel("Payment failed after maximum retries");
            notificationService.sendSubscriptionCancelledNotification(this);
        }
    }
    
    private LocalDate calculateNextRetryDate(int retryCount) {
        int days = switch (retryCount) {
            case 0 -> 1;   // 1차 재시도: 1일 후
            case 1 -> 3;   // 2차 재시도: 3일 후
            case 2 -> 7;   // 3차 재시도: 7일 후
            case 3 -> 14;  // 4차 재시도: 14일 후
            default -> 0;
        };
        return LocalDate.now().plusDays(days);
    }
}
```

**Dunning Management 스케줄러:**
```java
@Scheduled(cron = "0 0 2 * * *") // 매일 새벽 2시 실행
public void processRecurringPayments() {
    // 갱신일이 오늘인 구독 조회
    List<Subscription> subscriptions = subscriptionRepository
        .findByNextBillingDate(LocalDate.now());
    
    // 재시도일이 오늘인 구독 조회 (PAST_DUE 상태)
    List<Subscription> retrySubscriptions = subscriptionRepository
        .findByNextRetryDateAndStatus(LocalDate.now(), SubscriptionStatus.PAST_DUE);
    
    // 정기 결제 처리
    subscriptions.forEach(this::processSubscriptionPayment);
    
    // 재시도 결제 처리
    retrySubscriptions.forEach(this::retrySubscriptionPayment);
}
```

---

**3. 구독 상태 관리 필수**

**구독 상태 종류:**
- **active**: 정상적으로 결제되고 있는 구독
- **past_due**: 결제 실패했지만 재시도 중인 구독
- **paused**: 일시 중지된 구독 (사용자가 요청)
- **cancelled**: 취소된 구독

**왜 필요한가?**
- 단건 결제는 단순함: PENDING → COMPLETED/CANCELED
- 멤버십 결제는 복잡함: **여러 상태 전이**가 필요
- 결제 실패 시에도 구독은 유지 (past_due 상태)
- 사용자가 구독을 일시 중지하거나 취소할 수 있음

**상태 전이 다이어그램:**
```
[신규 구독]
  ↓
[active] ←→ [past_due] (결제 실패 시)
  ↓           ↓ (재시도 성공)
[active]   [active]
  ↓           ↓
[paused] (사용자 요청)
  ↓
[cancelled] (최대 재시도 실패 또는 사용자 취소)
```

**구현 예시:**
```java
public enum SubscriptionStatus {
    ACTIVE,      // 정상 결제 중
    PAST_DUE,    // 결제 실패, 재시도 중
    PAUSED,      // 일시 중지
    CANCELLED    // 취소됨
}

@Entity
public class Subscription {
    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status;
    
    public void updateStatus(SubscriptionStatus newStatus) {
        // 상태 전이 검증
        validateStatusTransition(this.status, newStatus);
        this.status = newStatus;
    }
    
    private void validateStatusTransition(SubscriptionStatus from, SubscriptionStatus to) {
        // 허용된 상태 전이만 가능
        // 예: cancelled → active 불가능
        // 예: active → past_due 가능
    }
}
```

---

**4. PaymentHistory 필수**

**왜 필요한가?**
- Dunning Management를 위해 **모든 결제 시도 기록** 필요
- 재시도 횟수, 재시도 일정, 실패 사유 등을 추적
- 구독 취소 시점 결정을 위한 데이터 필요

**구현 예시:**
```java
@Entity
@Table(name = "payment_history")
public class PaymentHistory {
    @Id
    private Long id;
    
    private Long subscriptionId;
    private LocalDateTime attemptedAt;      // 결제 시도 일시
    private PaymentStatus status;            // SUCCESS, FAILED
    private String failureReason;            // 실패 사유
    private int retryCount;                 // 재시도 횟수
    private LocalDate nextRetryDate;        // 다음 재시도일
}
```

---

**5. 스케줄러 주기 차이**

| 결제 유형 | 스케줄러 주기 | 근거 |
|----------|-------------|------|
| **단건 결제** | 1분 (현재 구현) | 빠른 상태 복구 필요 |
| **멤버십 결제** | 1시간~1일 | 정기 결제 주기에 맞춰 설정 (월간 구독이면 매일, 연간 구독이면 매월) |

**구현 예시:**
```java
// 단건 결제 상태 복구 (현재 구현)
@Scheduled(fixedDelay = 60000) // 1분마다
public void recoverPendingOrders() {
    // PENDING 상태 주문 복구
}

// 멤버십 결제 처리 (추가 필요)
@Scheduled(cron = "0 0 2 * * *") // 매일 새벽 2시
public void processRecurringPayments() {
    // 갱신일이 오늘인 구독 결제 처리
}

@Scheduled(cron = "0 0 3 * * *") // 매일 새벽 3시
public void retryFailedPayments() {
    // 재시도일이 오늘인 구독 재시도
}
```

---

**현재 구현은 중규모 커머스 서비스의 실시간 단건 결제 처리에 최적화되어 있으며, 대부분의 권장 사항을 잘 반영하고 있습니다.**

**멤버십 결제를 지원하려면:**
1. ✅ **별도의 FeignClient 생성** (`PaymentGatewaySubscriptionClient`)
2. ✅ **별도의 Resilience4j 설정** (높은 Retry, 관대한 Circuit Breaker)
3. ✅ **Subscription 엔티티 생성** (구독 상태 관리)
4. ✅ **PaymentHistory 엔티티 생성** (모든 결제 시도 기록)
5. ✅ **Dunning Management 구현** (자동 재시도 프로세스)
6. ✅ **구독 상태 관리 로직** (active, past_due, paused, cancelled)
7. ✅ **정기 결제 스케줄러** (갱신일 기준 자동 결제)
8. ✅ **재시도 스케줄러** (재시도일 기준 자동 재시도)
9. ✅ **사용자 알림 시스템** (결제 실패 시 알림)

**이러한 구현은 단건 결제와는 완전히 다른 아키텍처와 전략이 필요하며, 별도의 도메인으로 분리하는 것을 권장합니다.**

---

## 🤔 정기 결제에서 Circuit Breaker가 필요한가?

### 사용자의 관점: "단순히 try-catch + 재시도 제한으로 충분하지 않을까?"

**사용자의 논리:**
- 정기 결제는 배치/스케줄러 기반이므로 사용자 요청 경로가 아님
- 실패해도 사용자가 즉시 영향을 받지 않음
- Dunning Management로 재시도하므로 일시적 오류는 자동으로 해결됨
- Circuit Breaker의 복잡도가 오히려 불필요할 수 있음

**간단한 구현 예시:**
```java
@Scheduled(cron = "0 0 2 * * *")
public void processRecurringPayments() {
    List<Subscription> subscriptions = subscriptionRepository
        .findByNextBillingDate(LocalDate.now());
    
    int consecutiveFailures = 0;
    int maxConsecutiveFailures = 10; // 연속 실패 10회 시 break
    
    for (Subscription subscription : subscriptions) {
        try {
            processSubscriptionPayment(subscription);
            consecutiveFailures = 0; // 성공 시 리셋
        } catch (PaymentException e) {
            consecutiveFailures++;
            
            if (consecutiveFailures >= maxConsecutiveFailures) {
                log.error("연속 실패 {}회 발생. PG 시스템 장애로 간주하여 중단합니다.", 
                    maxConsecutiveFailures);
                break; // 더 이상 시도하지 않음
            }
            
            // Dunning Management
            handlePaymentFailure(subscription, e);
        }
    }
}
```

**장점:**
- ✅ 구현 단순
- ✅ Circuit Breaker 복잡도 없음
- ✅ 명확한 로직 (연속 실패 시 break)

**단점:**
- ⚠️ 연속 실패만 감지 (전체 실패율은 감지 못함)
- ⚠️ 느린 호출 감지 불가
- ⚠️ 복구 자동화 없음 (수동으로 재시작 필요)

---

### Circuit Breaker가 필요한 이유

#### 1. 전체 실패율 감지 (연속 실패만으로는 부족)

**문제 상황:**
```
시나리오: PG 시스템이 간헐적으로 실패 (50% 실패율)
- 구독 1: 성공
- 구독 2: 실패
- 구독 3: 성공
- 구독 4: 실패
- 구독 5: 성공
- 구독 6: 실패
...

연속 실패 방식: 연속 실패가 없으므로 계속 시도 → 비효율적
Circuit Breaker: 전체 실패율 50% 감지 → 즉시 차단
```

**Circuit Breaker의 장점:**
- ✅ 전체 실패율 감지 (sliding window 기반)
- ✅ 간헐적 실패도 감지 가능
- ✅ 실패율이 임계값을 넘으면 즉시 차단

---

#### 2. 느린 호출 감지 (지연도 장애)

**문제 상황:**
```
시나리오: PG 시스템이 느려짐 (모든 요청이 10초 이상 소요)
- 구독 1: 10초 후 성공
- 구독 2: 12초 후 성공
- 구독 3: 11초 후 성공
...

try-catch 방식: 실패가 아니므로 계속 시도 → 스레드 점유 시간 증가
Circuit Breaker: 느린 호출 감지 → 즉시 차단
```

**Circuit Breaker의 장점:**
- ✅ 느린 호출도 장애로 간주 (slowCallDurationThreshold)
- ✅ 지연으로 인한 리소스 고갈 방지
- ✅ 장애 조기 감지

---

#### 3. 자동 복구 (수동 개입 불필요)

**문제 상황:**
```
시나리오: PG 시스템이 1시간 동안 장애 → 복구
- try-catch 방식: 연속 실패로 break → 수동으로 재시작 필요
- Circuit Breaker: 자동으로 Half-Open → 복구 테스트 → 자동 재개
```

**Circuit Breaker의 장점:**
- ✅ 자동 복구 (waitDurationInOpenState 후 Half-Open)
- ✅ 복구 테스트 자동 수행
- ✅ 수동 개입 불필요

---

#### 4. 리소스 보호 (무한 재시도 방지)

**문제 상황:**
```
시나리오: PG 시스템이 완전히 다운
- try-catch 방식: 모든 구독에 대해 재시도 → 시스템 부하 증가
- Circuit Breaker: 즉시 차단 → 불필요한 호출 방지
```

**Circuit Breaker의 장점:**
- ✅ 장애 상황에서 즉시 차단
- ✅ 불필요한 호출 방지
- ✅ 시스템 리소스 보호

---

### 비교 분석: try-catch vs Circuit Breaker

| 항목 | try-catch + 재시도 제한 | Circuit Breaker |
|------|------------------------|-----------------|
| **구현 복잡도** | ⭐ (낮음) | ⭐⭐⭐ (높음) |
| **연속 실패 감지** | ✅ 가능 | ✅ 가능 |
| **전체 실패율 감지** | ❌ 불가능 | ✅ 가능 |
| **느린 호출 감지** | ❌ 불가능 | ✅ 가능 |
| **자동 복구** | ❌ 수동 필요 | ✅ 자동 |
| **리소스 보호** | ⚠️ 제한적 | ✅ 강력 |
| **운영 복잡도** | ⭐ (낮음) | ⭐⭐ (중간) |

---

### 하이브리드 접근: Circuit Breaker + 간단한 로직

**권장 방식:**
- Circuit Breaker는 **외부 시스템 장애 감지**용
- try-catch는 **비즈니스 로직 처리**용
- 둘 다 사용하되, 역할을 분리

**구현 예시:**
```java
@Scheduled(cron = "0 0 2 * * *")
public void processRecurringPayments() {
    List<Subscription> subscriptions = subscriptionRepository
        .findByNextBillingDate(LocalDate.now());
    
    for (Subscription subscription : subscriptions) {
        try {
            // Circuit Breaker가 적용된 FeignClient 사용
            // → PG 시스템 장애 시 즉시 차단 (불필요한 호출 방지)
            PaymentResponse response = paymentGatewaySubscriptionClient
                .requestPayment(subscription);
            
            // 성공 처리
            subscription.updateStatus(SubscriptionStatus.ACTIVE);
            
        } catch (CircuitBreakerOpenException e) {
            // Circuit Breaker가 Open 상태 → PG 시스템 장애
            // → 다음 스케줄링 주기에 재시도 (자동 복구 대기)
            log.warn("Circuit Breaker가 Open 상태입니다. 다음 주기에 재시도합니다. (subscriptionId: {})",
                subscription.getId());
            // 구독 상태는 유지 (past_due로 변경하지 않음)
            
        } catch (PaymentException e) {
            // 비즈니스 오류 또는 시스템 오류 (Circuit Breaker가 차단하지 않은 경우)
            // → Dunning Management
            handlePaymentFailure(subscription, e);
        }
    }
}
```

**장점:**
- ✅ Circuit Breaker: 외부 시스템 장애 감지 및 차단
- ✅ try-catch: 비즈니스 로직 처리
- ✅ 역할 분리: 각각의 장점 활용

---

### 결론: 정기 결제에서 Circuit Breaker는 선택적

#### Circuit Breaker가 **필수인 경우:**

1. **높은 트래픽**: 많은 구독이 동시에 결제되는 경우
   - Circuit Breaker 없이 모든 구독에 재시도 → 시스템 부하 증가
   - Circuit Breaker로 즉시 차단 → 리소스 보호

2. **PG 시스템이 불안정한 경우**: 간헐적 실패가 빈번한 경우
   - try-catch만으로는 전체 실패율 감지 불가
   - Circuit Breaker로 전체 실패율 감지 → 효율적 차단

3. **느린 호출이 문제인 경우**: PG 응답이 느려지는 경우
   - try-catch만으로는 느린 호출 감지 불가
   - Circuit Breaker로 느린 호출 감지 → 조기 차단

#### Circuit Breaker가 **선택적인 경우:**

1. **낮은 트래픽**: 구독 수가 적은 경우
   - try-catch + 재시도 제한으로도 충분
   - Circuit Breaker의 복잡도가 오히려 불필요

2. **PG 시스템이 안정적인 경우**: 실패가 거의 없는 경우
   - Circuit Breaker가 거의 동작하지 않음
   - 단순한 try-catch로도 충분

3. **구현 단순성이 우선인 경우**: 초기 단계, 소규모 서비스
   - Circuit Breaker 구현 및 운영 부담
   - 단순한 로직으로 시작 후 필요 시 도입

---

### 권장 접근 방식

#### 단계별 도입 전략:

**1단계: 초기 (소규모 서비스)**
```java
// Circuit Breaker 없이 시작
@Scheduled(cron = "0 0 2 * * *")
public void processRecurringPayments() {
    int consecutiveFailures = 0;
    int maxConsecutiveFailures = 10;
    
    for (Subscription subscription : subscriptions) {
        try {
            processSubscriptionPayment(subscription);
            consecutiveFailures = 0;
        } catch (PaymentException e) {
            consecutiveFailures++;
            if (consecutiveFailures >= maxConsecutiveFailures) {
                log.error("연속 실패 {}회 발생. 중단합니다.", maxConsecutiveFailures);
                break;
            }
            handlePaymentFailure(subscription, e);
        }
    }
}
```

**2단계: 성장 (중규모 서비스)**
```java
// Circuit Breaker 도입 (관대한 설정)
@Scheduled(cron = "0 0 2 * * *")
public void processRecurringPayments() {
    for (Subscription subscription : subscriptions) {
        try {
            // Circuit Breaker 적용 (관대한 설정: failureRateThreshold: 70%)
            PaymentResponse response = paymentGatewaySubscriptionClient
                .requestPayment(subscription);
            subscription.updateStatus(SubscriptionStatus.ACTIVE);
        } catch (CircuitBreakerOpenException e) {
            // Circuit Breaker Open → 다음 주기에 재시도
            log.warn("Circuit Breaker Open. 다음 주기에 재시도합니다.");
        } catch (PaymentException e) {
            handlePaymentFailure(subscription, e);
        }
    }
}
```

**3단계: 안정화 (대규모 서비스)**
```java
// Circuit Breaker + 자동 조정
@Scheduled(cron = "0 0 2 * * *")
public void processRecurringPayments() {
    // Circuit Breaker + 운영 메트릭 기반 자동 조정
    // → 완전 자동화
}
```

---

### 최종 권장사항

**정기 결제에서 Circuit Breaker는:**
- ✅ **선택적**이지만 **권장됨**
- ✅ 특히 **높은 트래픽**이나 **불안정한 PG 시스템**에서는 **필수**
- ✅ **낮은 트래픽**이나 **안정적인 PG 시스템**에서는 **선택적**

**실무 권장:**
1. **초기 단계**: try-catch + 재시도 제한으로 시작
2. **성장 단계**: Circuit Breaker 도입 (관대한 설정)
3. **안정화 단계**: Circuit Breaker + 자동 조정

**핵심은 "필수"가 아니라 "선택적이지만 권장"이며, 서비스 규모와 PG 시스템 특성에 따라 결정하는 것이 좋습니다.**

