# Lock 전략 설계

## 📌 개요

본 문서는 동시성 제어를 위한 Lock 전략을 설명합니다. 특히 **FOR UPDATE / FOR SHARE**의 작동 방식과 **Lock 범위 최소화 전략**을 다룹니다.

---

## 🔍 핵심 개념

### 1. FOR UPDATE / FOR SHARE

#### FOR UPDATE (PESSIMISTIC_WRITE)
- **목적**: 쓰기 작업 전에 행을 잠가 다른 트랜잭션의 수정을 방지
- **사용 시점**: 데이터를 읽은 후 수정할 때
- **동작**: `SELECT ... FOR UPDATE` 쿼리 실행 시 해당 행에 배타적 락 설정

#### FOR SHARE (PESSIMISTIC_READ)
- **목적**: 읽기 작업 중에 행이 변경되지 않도록 보장
- **사용 시점**: 읽은 데이터의 일관성을 보장해야 할 때
- **동작**: `SELECT ... FOR SHARE` 쿼리 실행 시 공유 락 설정

### 2. Lock 범위와 트랜잭션 범위

**핵심 원칙**: **Lock 범위는 트랜잭션 범위와 동일합니다.**

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. FOR UPDATE로 조회 → 락 획득
    User user = loadUserForUpdate(userId);
    Product product = productRepository.findByIdForUpdate(productId);
    
    // 2. 락이 유지되는 동안 데이터 수정
    user.deductPoint(Point.of(amount));
    product.decreaseStock(quantity);
    
    // 3. 트랜잭션 커밋 시 락 해제
    // 트랜잭션이 끝나면 자동으로 락이 해제됨
}
```

**Lock 생명주기**:
1. `SELECT ... FOR UPDATE` 실행 시 → 락 획득
2. 트랜잭션 내에서 락 유지
3. 트랜잭션 커밋/롤백 시 → 락 해제

---

## 🎯 Lock 전략 적용 사례

### 1. 재고 차감에서 PESSIMISTIC_WRITE 선택 근거

#### 문제 상황
동시에 여러 주문이 들어올 때, Lost Update 문제가 발생할 수 있습니다:

```
T1: 재고 조회 (stock = 10)
T2: 재고 조회 (stock = 10)  ← 동시에 같은 값 읽음
T1: 재고 차감 (10 - 3 = 7) → 저장
T2: 재고 차감 (10 - 5 = 5) → 저장  ← T1의 변경사항 손실!
```

#### 해결 방법: PESSIMISTIC_WRITE 사용

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 비관적 락을 사용하여 상품 조회 (재고 차감 시 동시성 제어)
    Product product = productRepository.findByIdForUpdate(command.productId())
        .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
            String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", command.productId())));
    
    // 락이 걸린 상태에서 재고 차감
    product.decreaseStock(command.quantity());
    productRepository.save(product);
}
```

**동작 원리**:
1. `findByIdForUpdate()` 실행 → `SELECT ... FOR UPDATE` 쿼리 실행
2. 해당 행에 배타적 락 설정
3. 다른 트랜잭션은 같은 행을 읽을 수 없음 (대기)
4. 재고 차감 후 커밋 → 락 해제
5. 대기 중이던 트랜잭션이 최신 값을 읽어 처리

**선택 근거**:
- ✅ **Lost Update 방지**: 동시 수정 시 데이터 손실 방지
- ✅ **데이터 일관성 보장**: 재고가 음수가 되는 것을 방지
- ✅ **비즈니스 정확성**: 재고 차감이 정확하게 반영됨

### 2. 포인트 차감에서 PESSIMISTIC_WRITE 선택 근거

#### 문제 상황
동시에 여러 주문이 들어올 때, 포인트가 중복 차감되거나 음수가 될 수 있습니다:

```
T1: 포인트 조회 (point = 100,000)
T2: 포인트 조회 (point = 100,000)  ← 동시에 같은 값 읽음
T1: 포인트 차감 (100,000 - 30,000 = 70,000) → 저장
T2: 포인트 차감 (100,000 - 50,000 = 50,000) → 저장  ← T1의 변경사항 손실!
```

#### 해결 방법: PESSIMISTIC_WRITE 사용

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 비관적 락을 사용하여 사용자 조회 (포인트 차감 시 동시성 제어)
    User user = loadUserForUpdate(userId);
    
    // 락이 걸린 상태에서 포인트 차감
    user.deductPoint(Point.of(totalAmount));
    userRepository.save(user);
}
```

**선택 근거**:
- ✅ **Lost Update 방지**: 동시 포인트 차감 시 데이터 손실 방지
- ✅ **포인트 음수 방지**: 도메인 레벨 검증과 함께 이중 보호
- ✅ **재무 정확성**: 포인트 차감이 정확하게 반영됨

---

## 🔒 Lock 범위 최소화 전략

### 핵심 원칙: 인덱스를 기준으로 Lock이 걸린다

**MySQL에서 FOR UPDATE는 인덱스를 기준으로 Lock이 걸립니다.**

### ⚠️ 중요한 오해: Full Scan ≠ 테이블 전체 락

**Full scan이 발생해도 테이블 전체에 락이 걸리는 것은 아닙니다.**

- ❌ **테이블 레벨 락 아님**: 테이블 전체를 잠그는 것이 아니라
- ✅ **행 레벨 락**: 스캔하는 행들에 개별적으로 락이 걸림
- ⚠️ **문제**: Full scan 시 많은 행을 스캔하므로 많은 행에 락이 걸릴 수 있음

**예시**:
```sql
-- 인덱스 없는 컬럼으로 조회
SELECT * FROM product WHERE name = '상품명' FOR UPDATE;

-- 테이블에 10,000개 행이 있다면:
-- 1. Full scan으로 10,000개 행 모두 스캔
-- 2. 조건에 맞는 1개 행 발견
-- 3. 하지만 스캔 경로상의 많은 행에 락이 걸릴 수 있음
-- 4. 실제로는 수백~수천 개 행에 락이 걸릴 수 있음
```

#### 인덱스가 있는 경우 (권장)

```sql
-- Product 테이블: id (PK, 인덱스 있음)
SELECT * FROM product WHERE id = 1 FOR UPDATE;
-- → id = 1인 행만 락 (Lock 범위 최소화)
```

**Lock 범위**: 해당 행만 락

#### 인덱스가 없는 경우 (비권장)

```sql
-- Product 테이블: name (인덱스 없음)
SELECT * FROM product WHERE name = '상품명' FOR UPDATE;
-- → 전체 테이블 스캔 필요
```

**Lock 범위**: 
- ❌ **테이블 전체에 락이 걸리는 것은 아님** (테이블 레벨 락 아님)
- ⚠️ **스캔 경로상의 행들에 락이 걸림**:
  - Full scan 시 조건에 맞는 행뿐만 아니라 스캔하는 모든 행에 락이 걸릴 수 있음
  - MySQL InnoDB의 REPEATABLE READ 격리 수준에서는 Gap Lock/Next-Key Lock으로 인해 더 넓은 범위에 락이 걸릴 수 있음
  - 예: 10,000개 행을 스캔하면 수천 개 행에 락이 걸릴 수 있음

**실제 동작**:
```
1. Full scan 시작 → 첫 번째 행부터 순차적으로 스캔
2. 각 행을 스캔하면서 조건 확인
3. 조건에 맞는 행 발견 시 → 해당 행에 락 설정
4. 스캔이 끝날 때까지 락 유지
5. 트랜잭션 커밋 시 락 해제
```

### 📖 구체적인 예시: 경로상의 행에 락이 걸리는 과정

#### 시나리오: 인덱스 없는 name 컬럼으로 조회

**테이블 상태**:
```
product 테이블 (10,000개 행)
- id (PK, 인덱스 있음)
- name (인덱스 없음)
- stock
```

**쿼리**:
```sql
SELECT * FROM product WHERE name = '특가상품' FOR UPDATE;
```

**실제 동작 과정**:

```
1단계: Full Scan 시작
   ↓
   행 1: name = '상품A'     → 스캔, 조건 불일치, 락 X
   행 2: name = '상품B'     → 스캔, 조건 불일치, 락 X
   행 3: name = '상품C'     → 스캔, 조건 불일치, 락 X
   ...
   행 500: name = '상품500' → 스캔, 조건 불일치, 락 X
   행 501: name = '특가상품' → 스캔, 조건 일치! → 🔒 락 설정
   행 502: name = '상품502' → 스캔, 조건 불일치, 락 X
   ...
   행 10,000: name = '상품Z' → 스캔, 조건 불일치, 락 X
   ↓
2단계: 스캔 완료
```

**문제**: REPEATABLE READ 격리 수준에서는 **Gap Lock**과 **Next-Key Lock**이 적용됩니다.

#### 실제 락이 걸리는 범위 (REPEATABLE READ 격리 수준)

**인덱스가 없는 경우**:
```
테이블에 실제로 락이 걸리는 행들:

행 1-500:   스캔 경로상의 행들 → 일부에 락이 걸릴 수 있음
행 501:     조건에 맞는 행 → 🔒 Record Lock
행 501-502: Gap Lock (간격 락) → 🔒
행 502-10,000: 스캔 경로상의 행들 → 일부에 락이 걸릴 수 있음

결과: 수백~수천 개 행에 락이 걸릴 수 있음! ⚠️
```

**인덱스가 있는 경우 (PK 기반)**:
```sql
SELECT * FROM product WHERE id = 501 FOR UPDATE;
```

```
인덱스를 통해 바로 행 501로 이동:
  ↓
행 501: 조건에 맞는 행 → 🔒 Record Lock만

결과: 행 501만 락! ✅
```

#### 📊 비교 예시

**상황**: 10,000개 행 중 name = '특가상품'인 행 1개를 찾는 경우

| 조회 방식 | 스캔 범위 | 락이 걸리는 행 수 | 성능 |
|----------|----------|-----------------|------|
| **인덱스 없음** (name 컬럼) | 10,000개 행 전체 스캔 | 수백~수천 개 행 | ❌ 매우 느림 |
| **인덱스 있음** (id 컬럼) | 1개 행만 접근 | 1개 행 | ✅ 매우 빠름 |

#### 🎯 실제 시나리오 예시

**시나리오 1: 인덱스 없는 조회**

```sql
-- 트랜잭션 1
BEGIN;
SELECT * FROM product WHERE name = '특가상품' FOR UPDATE;
-- → 10,000개 행을 스캔하면서 수백 개 행에 락 설정
-- → 아직 커밋 안 함

-- 트랜잭션 2 (동시 실행)
BEGIN;
SELECT * FROM product WHERE name = '다른상품' FOR UPDATE;
-- → 락이 걸린 행들을 만나면 대기! ⏳
-- → 트랜잭션 1이 커밋할 때까지 기다려야 함
```

**시나리오 2: 인덱스 있는 조회**

```sql
-- 트랜잭션 1
BEGIN;
SELECT * FROM product WHERE id = 501 FOR UPDATE;
-- → 인덱스로 바로 행 501로 이동
-- → 행 501만 락 ✅

-- 트랜잭션 2 (동시 실행)
BEGIN;
SELECT * FROM product WHERE id = 502 FOR UPDATE;
-- → 인덱스로 바로 행 502로 이동
-- → 행 502만 락 ✅
-- → 트랜잭션 1과 충돌 없음! 동시 실행 가능 ✅
```

#### 💡 핵심 포인트

1. **Full Scan = 경로상의 모든 행을 지나감**
   - 마치 책을 처음부터 끝까지 읽는 것과 같음
   - 읽는 과정에서 지나간 페이지들에 "읽는 중" 표시가 남음

2. **인덱스 = 바로 해당 페이지로 이동**
   - 책의 목차를 보고 바로 해당 페이지로 이동
   - 다른 페이지는 건드리지 않음

3. **Lock 범위 = 지나간 경로의 범위**
   - Full Scan: 지나간 모든 행에 락이 걸릴 수 있음
   - 인덱스: 바로 해당 행만 락

**문제점**:
- 불필요한 행까지 락이 걸려 동시성 처리량 감소
- 대량의 행에 락이 걸려 다른 트랜잭션 대기 시간 증가
- 데드락 발생 가능성 증가

### 인덱스 설계가 Lock 범위에 미치는 영향

#### ✅ 올바른 설계 (PK 기반 조회)

```java
// Product 엔티티
@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;  // PK, 자동으로 인덱스 생성
    
    // ...
}

// Repository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :productId")
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
```

**장점**:
- PK는 자동으로 인덱스가 생성됨
- Lock 범위가 최소화됨 (해당 행만 락)
- 성능 최적화

#### ❌ 잘못된 설계 (인덱스 없는 컬럼 조회)

```java
// 인덱스가 없는 name 컬럼으로 조회
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.name = :name")
Optional<Product> findByNameForUpdate(@Param("name") String name);
```

**문제점**:
- 전체 테이블 스캔 필요
- 스캔 경로상의 많은 행에 락이 걸림 (테이블 전체는 아님)
- 성능 저하 및 동시성 처리량 감소
- Gap Lock/Next-Key Lock으로 인해 더 넓은 범위에 락이 걸릴 수 있음

### 현재 프로젝트의 인덱스 설계

#### 1. Product 조회 (재고 차감)

```java
// PK 기반 조회 → 인덱스 활용
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :productId")
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
```

**인덱스**: `product.id` (PK, 자동 인덱스)
**Lock 범위**: 해당 행만 락 ✅

#### 2. User 조회 (포인트 차감)

```java
// userId 기반 조회 → UNIQUE 인덱스 활용
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT u FROM User u WHERE u.userId = :userId")
Optional<User> findByUserIdForUpdate(@Param("userId") String userId);
```

**인덱스**: `user.user_id` (UNIQUE 제약조건, 인덱스 자동 생성)
**Lock 범위**: 해당 행만 락 ✅

---

## 🔐 MySQL InnoDB의 Lock 종류

### 1. Record Lock (행 락)
- **대상**: 특정 행에만 락
- **사용**: 인덱스가 있는 경우
- **예시**: `SELECT * FROM product WHERE id = 1 FOR UPDATE;`
- **Lock 범위**: id = 1인 행만 락 ✅

### 2. Gap Lock (간격 락)
- **대상**: 인덱스 레코드 사이의 간격에 락
- **목적**: Phantom Read 방지
- **사용**: REPEATABLE READ 격리 수준에서 자동 사용
- **예시**: 
  ```sql
  -- id가 1, 3, 5인 행이 있다면
  SELECT * FROM product WHERE id BETWEEN 2 AND 4 FOR UPDATE;
  -- → id = 2, 4인 행은 없지만, 그 간격에 락이 걸림
  ```

### 3. Next-Key Lock (다음 키 락)
- **대상**: Record Lock + Gap Lock의 조합
- **목적**: Phantom Read 완전 방지
- **사용**: REPEATABLE READ 격리 수준에서 기본 사용
- **예시**:
  ```sql
  -- id가 1, 3, 5인 행이 있다면
  SELECT * FROM product WHERE id = 3 FOR UPDATE;
  -- → id = 3인 행 + (3, 5) 간격에 락
  ```

### Full Scan 시 Lock 범위 확대 이유

**인덱스가 없으면**:
1. Full scan으로 모든 행을 스캔
2. 조건에 맞는 행 발견 시 → Record Lock
3. REPEATABLE READ 격리 수준에서는 Gap Lock/Next-Key Lock도 함께 적용
4. 결과적으로 스캔 경로상의 많은 행과 간격에 락이 걸림

**인덱스가 있으면**:
1. 인덱스를 통해 바로 해당 행으로 이동
2. 해당 행에만 Record Lock
3. Gap Lock 범위도 최소화
4. 결과적으로 해당 행만 락 ✅

---

## 📊 Lock 전략 비교

| 전략 | 사용 시점 | Lock 범위 | 성능 | 일관성 |
|------|----------|-----------|------|--------|
| **PESSIMISTIC_WRITE** | 쓰기 전 읽기 | 인덱스 기준 (최소화) | 중간 | 높음 ✅ |
| **PESSIMISTIC_READ** | 읽기 일관성 보장 | 인덱스 기준 (최소화) | 중간 | 높음 ✅ |
| **낙관적 락** | 충돌 빈도 낮을 때 | 없음 | 높음 | 낮음 ⚠️ |
| **락 없음** | 동시성 문제 없을 때 | 없음 | 높음 | 낮음 ❌ |

---

## 🎯 실전 적용 가이드

### 1. Lock이 필요한 경우

✅ **반드시 Lock 필요**:
- 재고 차감 (Lost Update 방지)
- 포인트 차감 (재무 정확성)
- 주문 생성 (원자성 보장)

✅ **Lock 고려**:
- 집계 쿼리 (Phantom Read 방지)
- 범위 쿼리 (일관성 보장)

### 2. Lock이 불필요한 경우

❌ **Lock 불필요**:
- 단순 조회 (읽기 전용)
- 통계 조회 (약간의 불일치 허용 가능)
- 캐시 가능한 데이터

### 3. Lock 범위 최소화 체크리스트

- [ ] PK 또는 인덱스가 있는 컬럼으로 조회하는가?
- [ ] 필요한 행만 락을 걸고 있는가?
- [ ] 트랜잭션 범위를 최소화했는가?
- [ ] 불필요한 락을 사용하지 않는가?

---

## 📝 코드 예시

### PurchasingFacade.createOrder()

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 포인트 차감을 위한 사용자 조회 (PESSIMISTIC_WRITE)
    //    - userId는 UNIQUE 인덱스가 있어 Lock 범위 최소화
    //    - Lost Update 방지
    User user = loadUserForUpdate(userId);
    
    // 2. 재고 차감을 위한 상품 조회 (PESSIMISTIC_WRITE)
    //    - id는 PK 인덱스가 있어 Lock 범위 최소화
    //    - Lost Update 방지
    Product product = productRepository.findByIdForUpdate(command.productId())
        .orElseThrow(...);
    
    // 3. 락이 걸린 상태에서 데이터 수정
    product.decreaseStock(command.quantity());
    user.deductPoint(Point.of(totalAmount));
    
    // 4. 트랜잭션 커밋 시 락 자동 해제
    return OrderInfo.from(savedOrder);
}
```

---

## 🔗 관련 문서

- [트랜잭션 격리 수준 분석](./transaction-isolation-analysis.md)
- [ERD 설계](./04-erd.md)
- [Aggregate 분석](./06-aggregate-analysis.md)

---

## 📚 참고 자료

- MySQL InnoDB Locking: https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html
- JPA Lock Modes: https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking
- Spring Data JPA Lock: https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#locking

