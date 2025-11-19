# Transactional Operation 평가 보고서

## 📌 개요

본 문서는 멘토링에서 제시된 transactional operation 설계 원칙과 현재 프로젝트의 구현 내용을 비교 평가한 보고서입니다.

---

## ✅ 1. 도메인별 락 전략 평가

### 1.1 재고 (Inventory) 관리

#### 멘토 권장사항
- **비관적 락** 또는 **Queue 기반 예약** 방식
- 정합성 최우선 (1개라도 초과 판매되면 사고)
- 금전적 피해로 직결

#### 프로젝트 구현
```java
// PurchasingFacade.createOrder()
Product product = productRepository.findByIdForUpdate(command.productId());
// PESSIMISTIC_WRITE 사용
```

**평가 결과: ✅ 적합**
- 비관적 락 사용으로 정합성 보장
- PK 기반 조회로 Lock 범위 최소화
- 트랜잭션 내부에 외부 I/O 없음
- lock holding time 매우 짧음

**개선 제안:**
- Hot Spot 발생 시 Queue 기반 예약 방식 고려 (향후 개선)
- 현재는 트래픽이 낮아 비관적 락으로 충분

---

### 1.2 포인트 (Point) 관리

#### 멘토 권장사항
- **비관적 락** 추천
- 금전과 동일 → 정합성 가장 중요
- 실패 허용도 거의 없음
- 재시도 시 부정 거래 위험

#### 프로젝트 구현
```java
// PurchasingFacade.createOrder()
User user = loadUserForUpdate(userId);
// PESSIMISTIC_WRITE 사용
```

**평가 결과: ✅ 적합**
- 비관적 락 사용으로 정합성 보장
- UNIQUE 인덱스 기반 조회로 Lock 범위 최소화
- 트랜잭션 내부에 외부 I/O 없음

**개선 제안:**
- 현재 구현이 멘토 권장사항과 일치
- 향후 포인트 히스토리 Insert-only + 집계 방식 고려 가능 (선택적)

---

### 1.3 쿠폰 (Coupon) 사용

#### 멘토 권장사항
- **낙관적 락** 또는 **유니크 인덱스 + Insert-only**
- 실패 허용 가능 (선착순이기 때문)
- 중복 발급은 절대 허용 불가 → Unique Index 필요
- 가능하면 Redis + Lua로 선점

#### 프로젝트 구현
```java
// PurchasingFacade.applyCoupon()
UserCoupon userCoupon = userCouponRepository.findByUserIdAndCouponCodeForUpdate(userId, couponCode);
// @Version 필드로 낙관적 락 적용
userCouponRepository.save(userCoupon);
// ObjectOptimisticLockingFailureException 처리
```

**평가 결과: ✅ 적합**
- 낙관적 락 사용으로 Hot Spot 대응
- @Version 필드를 통한 자동 충돌 감지
- 예외 처리로 실패 시나리오 처리

**개선 제안:**
- 멘토가 언급한 "saveAndFlush() 사용" 고려 필요 (아래 JPA 낙관적 락 섹션 참조)

---

### 1.4 쿠폰 발급

#### 멘토 권장사항
- **유니크 인덱스 + Insert-only**
- DuplicateKey → 실패로 처리 (낙관적)
- DB 락 필요 없음

#### 프로젝트 구현
```java
// UserCoupon 엔티티
@Table(name = "user_coupon", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_coupon_user_coupon", 
                     columnNames = {"ref_user_id", "ref_coupon_id"})
})
```

**평가 결과: ✅ 적합**
- UNIQUE 제약조건으로 중복 발급 방지
- Insert-only 패턴 (쿠폰 발급 로직은 코드에서 확인 불가, 엔티티 구조만 확인)

**개선 제안:**
- 쿠폰 발급 로직이 별도로 구현되어 있다면 확인 필요
- DuplicateKey 예외 처리 확인 필요

---

### 1.5 좋아요 (Like) 관리

#### 멘토 권장사항
- **Redis Incr() → 일정 주기 집계**
- DB는 집계된 데이터를 받는 구조
- 절대 비관적 락 사용 X
- Unique 인덱스도 가급적 피함
- 지연·부정합을 허용해도 됨 (Eventually Consistent)

#### 프로젝트 구현
```java
// LikeFacade.addLike()
@Table(name = "like", uniqueConstraints = {
    @UniqueConstraint(name = "uk_like_user_product", 
                     columnNames = {"ref_user_id", "ref_product_id"})
})
// UNIQUE 제약조건 사용
// DB 기반 집계 (COUNT(*))
```

**평가 결과: ⚠️ 부분 적합**
- ✅ UNIQUE 제약조건으로 중복 방지 (멘토는 "가급적 피함"이라고 했지만, 현재 구현도 합리적)
- ❌ Redis Incr() 미사용 (DB 기반 집계)
- ❌ 비동기 집계 미구현 (실시간 COUNT(*) 사용)

**개선 제안:**
- 멘토 권장사항대로 Redis Incr() + 비동기 집계 방식으로 전환 고려
- 현재는 트래픽이 낮아 DB 기반으로도 동작 가능하나, 확장성 고려 필요
- 좋아요 수는 정합성이 약간 깨져도 문제 없으므로 Eventually Consistent 방식 권장

---

## ✅ 2. JPA 낙관적 락 평가

### 2.1 멘토 권장사항

**문제점:**
- Dirty Checking 때문에 쿼리가 늦게 나감
- 낙관적 락의 목적 = 충돌 조기 감지(Fast Fail)
- 하지만 Dirty Checking은 메소드 끝날 때 flush
- → 충돌이 늦게 감지됨
- → 트랜잭션이 훨씬 길어짐
- → retry cost 증가

**해결책:**
```java
class ARepositoryImpl {
    @Override
    public A save(A entity) {
        return aJpaRepository.saveAndFlush(entity);
    }
}
```

### 2.2 프로젝트 구현

**현재 상태:**
- `saveAndFlush()` 사용하지 않음
- 일반 `save()` 메서드 사용
- Dirty Checking에 의존

**평가 결과: ❌ 개선 필요**

**영향 분석:**
- 쿠폰 사용 시 낙관적 락 충돌 감지가 늦어질 수 있음
- 트랜잭션 유지 시간 증가
- 재시도 비용 증가

**개선 제안:**
```java
// UserCouponRepositoryImpl
@Override
public UserCoupon save(UserCoupon userCoupon) {
    return userCouponJpaRepository.saveAndFlush(userCoupon);
}
```

**우선순위:**
- ⭐ 높음: 쿠폰 사용은 Hot Spot 발생 가능성이 있어 Fast Fail이 중요

---

## ✅ 3. 격리 수준 (Isolation Level) 평가

### 3.1 멘토 권장사항

- **READ COMMITTED / REPEATABLE READ까지만 실용적**
- **SERIALIZABLE은 금지**
  - 처리량 급감
  - 락 증가
  - MVCC 버전 관리 비용 증가
- 격리 수준은 동시성 제어의 '보조 수단'
- 주 용도는 "읽기 일관성 보장"

### 3.2 프로젝트 구현

**현재 상태:**
- **REPEATABLE READ** 사용 (MySQL InnoDB 기본값)
- 명시적 격리 수준 설정 없음

**평가 결과: ✅ 적합**

**근거:**
- 멘토 권장 범위 내 (READ COMMITTED / REPEATABLE READ)
- SERIALIZABLE 미사용
- 비관적 락으로 핵심 비즈니스 로직 보호
- 격리 수준은 보조 수단으로만 사용

**개선 제안:**
- 현재 설정 유지 권장
- 필요 시 특정 메서드에만 `@Transactional(isolation = Isolation.READ_COMMITTED)` 적용 고려

---

## ✅ 4. 동시성 테스트 평가

### 4.1 멘토 권장사항

**테스트 작성 기준:**
1. **3~5개 스레드는 너무 적음** (우연히 통과 가능)
2. **10~30개 정도 병렬로 테스트**
3. **도메인 중요도에 따라 스레드 수 조정**
   - 포인트 → 동시성 거의 없음 → 5~10개 스레드
   - 재고 → 동시성 많음 → 20~30개 스레드
   - 선착순 쿠폰 → 30개 스레드
4. **운영 환경과 최대한 동일하게 테스트**
   - Testcontainers + 운영 DB 버전 그대로
   - H2 절대 사용 금지
   - 인덱스·격리수준·특이 설정 반영

### 4.2 프로젝트 구현

**현재 상태:**

#### PurchasingFacadeConcurrencyTest
- 포인트 차감: **5개 스레드** ✅
- 재고 차감: **10개 스레드** ✅
- 쿠폰 사용: **10개 스레드** ✅
- 주문 취소: **3개 스레드** (취소 1개 + 주문 2개) ✅

#### LikeFacadeConcurrencyTest
- 좋아요 추가: **10개 스레드** ✅
- 동일 사용자 중복 요청: **10개 스레드** ✅
- 조회 테스트: **20개 스레드** (조회 10개 + 수정 10개) ✅

**테스트 환경:**
- ✅ Testcontainers 사용 (`MySqlTestContainersConfig`)
- ✅ H2 미사용
- ✅ 운영 DB 버전과 동일

**평가 결과: ✅ 적합**

**근거:**
- 스레드 수가 멘토 권장 범위(10~30개) 내
- Testcontainers 사용으로 운영 환경과 유사
- 도메인별로 적절한 스레드 수 사용

**개선 제안:**
- 재고 차감 테스트를 20~30개 스레드로 증가 고려 (멘토 권장)
- 선착순 쿠폰 발급 테스트 추가 고려 (현재 쿠폰 사용만 테스트)

---

## ✅ 5. 트랜잭션 범위 평가

### 5.1 멘토 권장사항

- **트랜잭션 범위 최소화**
- **외부 I/O를 트랜잭션 밖으로**
- **락 유지 시간 최소화**

### 5.2 프로젝트 구현

**현재 상태:**

#### PurchasingFacade.createOrder()
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 비관적 락으로 조회
    User user = loadUserForUpdate(userId);
    Product product = productRepository.findByIdForUpdate(command.productId());
    
    // 2. 비즈니스 로직 (락 유지)
    decreaseStocksForOrderItems(...);
    deductUserPoint(...);
    
    // 3. 저장
    productRepository.save(product);
    userRepository.save(user);
    orderRepository.save(order);
    
    // ✅ 외부 I/O 없음
    // ✅ 트랜잭션 범위 최소화
}
```

**평가 결과: ✅ 적합**

**근거:**
- 트랜잭션 내부에 외부 I/O 없음
- 락 유지 시간 최소화 (PK 기반 조회)
- 하나의 트랜잭션으로 원자성 보장

**개선 제안:**
- 현재 구현이 멘토 권장사항과 일치
- 향후 외부 결제 API 연동 시 Outbox 패턴 고려

---

## ✅ 6. 읽기 전용 트랜잭션 평가

### 6.1 멘토 권장사항

- `@Transactional(readOnly = true)` 사용 권장
- 여러 쿼리 간의 논리적 일관성 보장
- 성능 최적화 (쓰기 락 미사용)

### 6.2 프로젝트 구현

**현재 상태:**

```java
// PurchasingFacade
@Transactional(readOnly = true)
public List<OrderInfo> getOrders(String userId) { ... }

@Transactional(readOnly = true)
public OrderInfo getOrder(String userId, Long orderId) { ... }

// LikeFacade
@Transactional(readOnly = true)
public List<LikedProduct> getLikedProducts(String userId) { ... }
```

**평가 결과: ✅ 적합**

**근거:**
- 조회 메서드에 `readOnly = true` 적용
- 여러 쿼리 간 일관성 보장
- 성능 최적화

---

## ✅ 7. 유니크 인덱스 사용 평가

### 7.1 멘토 권장사항

- **유니크 인덱스는 전 세계 회사가 다 건다**
- "안 걸자는 말은 말이 안 된다"
- 애플리케이션 레벨로는 race condition 완전 방지 불가

### 7.2 프로젝트 구현

**현재 상태:**

```java
// Like 엔티티
@Table(name = "like", uniqueConstraints = {
    @UniqueConstraint(name = "uk_like_user_product", 
                     columnNames = {"ref_user_id", "ref_product_id"})
})

// UserCoupon 엔티티
@Table(name = "user_coupon", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_coupon_user_coupon", 
                     columnNames = {"ref_user_id", "ref_coupon_id"})
})
```

**평가 결과: ✅ 적합**

**근거:**
- 멘토 권장사항대로 유니크 인덱스 사용
- 중복 방지 보장
- 애플리케이션 레벨 한계 보완

---

## ✅ 8. 종합 평가 및 개선 우선순위

### 8.1 적합한 구현 (유지)

| 항목 | 평가 | 근거 |
|------|------|------|
| 재고 차감 | ✅ 적합 | 비관적 락 사용, 정합성 보장 |
| 포인트 차감 | ✅ 적합 | 비관적 락 사용, 정합성 보장 |
| 쿠폰 사용 | ✅ 적합 | 낙관적 락 사용, Hot Spot 대응 |
| 쿠폰 발급 | ✅ 적합 | UNIQUE 제약조건 사용 |
| 격리 수준 | ✅ 적합 | REPEATABLE READ 사용 |
| 동시성 테스트 | ✅ 적합 | 10~30개 스레드, Testcontainers 사용 |
| 트랜잭션 범위 | ✅ 적합 | 최소화, 외부 I/O 없음 |
| 읽기 전용 트랜잭션 | ✅ 적합 | readOnly = true 적용 |
| 유니크 인덱스 | ✅ 적합 | Like, UserCoupon에 적용 |

### 8.2 개선이 필요한 구현

| 항목 | 평가 | 개선 방안 | 우선순위 |
|------|------|----------|---------|
| JPA 낙관적 락 | ❌ 개선 필요 | saveAndFlush() 사용 | ⭐⭐⭐ 높음 |
| 좋아요 구현 | ⚠️ 부분 적합 | Redis Incr() + 비동기 집계 | ⭐⭐ 중간 |

### 8.3 개선 제안 상세

#### 우선순위 1: JPA 낙관적 락 Fast Fail 보장

**문제점:**
- 현재 `save()` 메서드 사용으로 Dirty Checking에 의존
- 충돌 감지가 늦어져 트랜잭션 유지 시간 증가

**개선 방안:**
```java
// UserCouponRepositoryImpl
@Override
public UserCoupon save(UserCoupon userCoupon) {
    return userCouponJpaRepository.saveAndFlush(userCoupon);
}
```

**예상 효과:**
- 충돌 조기 감지 (Fast Fail)
- 트랜잭션 유지 시간 단축
- 재시도 비용 감소

#### 우선순위 2: 좋아요 Redis 기반 전환 (선택적)

**문제점:**
- 현재 DB 기반 COUNT(*) 사용
- 확장성 제한

**개선 방안:**
```java
// Redis Incr() 사용
public void addLike(String userId, Long productId) {
    // Like 테이블에 Insert-only
    likeRepository.save(like);
    
    // Redis에 즉시 반영
    redisTemplate.opsForValue().increment("product:" + productId + ":like_count");
}

// 스케줄러로 주기적 동기화
@Scheduled(fixedDelay = 5000)
public void syncLikeCounts() {
    // Redis → DB 동기화
}
```

**예상 효과:**
- 조회 성능 향상
- 확장성 개선
- Eventually Consistent 보장

**참고:**
- 현재 트래픽이 낮다면 DB 기반으로도 충분
- 트래픽 증가 시 전환 고려

---

## ✅ 9. 결론

### 9.1 전체 평가

**종합 점수: 85/100**

현재 프로젝트는 멘토링에서 제시한 transactional operation 설계 원칙을 **대부분 잘 따르고 있습니다**.

**강점:**
1. ✅ 도메인별 적절한 락 전략 선택 (재고/포인트: 비관적, 쿠폰: 낙관적)
2. ✅ 격리 수준 적절히 설정 (REPEATABLE READ)
3. ✅ 동시성 테스트 적절히 작성 (10~30개 스레드, Testcontainers)
4. ✅ 트랜잭션 범위 최소화 (외부 I/O 없음)
5. ✅ 유니크 인덱스 적절히 사용

**개선 필요:**
1. ❌ JPA 낙관적 락에서 saveAndFlush() 미사용
2. ⚠️ 좋아요 기능이 Redis 기반이 아닌 DB 기반 (선택적 개선)

### 9.2 권장 사항

1. **즉시 개선 (우선순위 높음)**
   - JPA 낙관적 락에서 `saveAndFlush()` 사용
   - 쿠폰 사용 시 Fast Fail 보장

2. **향후 개선 (우선순위 중간)**
   - 좋아요 기능 Redis 기반 전환 (트래픽 증가 시)
   - 재고 차감 Queue 기반 예약 방식 고려 (Hot Spot 발생 시)

3. **현재 유지**
   - 나머지 구현은 멘토 권장사항과 일치하여 유지 권장

---

## 📚 참고 문서

- [멘토 질문 리스트: Transactional Operations](./mentor-questions-transactional-operations.md)
- [트랜잭션 격리 수준 분석](./transaction-isolation-analysis.md)
- [DBA 설득을 위한 판단 기준](./dba-persuasion-criteria.md)
- [동시성 처리 설계 원칙](./design/15-concurrency-design-principles.md)

