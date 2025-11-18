# 트랜잭션 관리 관행 및 데드락 방지 전략

## 📌 개요

본 문서는 규모가 큰 서비스에서 트랜잭션 설정에 대한 일반적인 관행과 데드락 방지 전략을 정리합니다.

---

## 🎯 핵심 질문: 트랜잭션을 최소화할까, 엄격하게 설정할까?

### 결론: **엄격하게 트랜잭션을 설정하는 것이 일반적입니다** ✅

**이유**:
1. **데이터 일관성 보장**: 트랜잭션이 없으면 부분 실패 시 데이터 불일치 발생
2. **Spring의 권장 사항**: 애플리케이션 서비스 레이어에서 트랜잭션 관리
3. **데드락은 트랜잭션 부재로 해결되지 않음**: 오히려 트랜잭션 범위 최소화와 락 순서 일관성으로 해결

---

## 📊 규모가 큰 서비스에서의 일반적인 관행

### 1. 트랜잭션 설정 원칙

#### ✅ 권장: 엄격하게 트랜잭션 설정

**애플리케이션 서비스 레이어 (Facade/Service)**:
- **모든 public 메서드에 `@Transactional` 설정**
- **읽기 전용 메서드는 `@Transactional(readOnly = true)` 명시**
- **쓰기 메서드는 일반 `@Transactional` 사용**

**예시 (현재 프로젝트)**:
```java
@Component
public class PurchasingFacade {
    
    // ✅ 쓰기 작업: 일반 @Transactional
    @Transactional
    public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
        // 주문 생성, 재고 차감, 포인트 차감
    }
    
    // ✅ 읽기 작업: readOnly = true 명시
    @Transactional(readOnly = true)
    public List<OrderInfo> getOrders(String userId) {
        // 주문 목록 조회
    }
}
```

#### ❌ 비권장: 트랜잭션 최소화

**트랜잭션을 최소화하는 경우의 문제점**:
```java
@Component
public class PurchasingFacade {
    
    // ❌ 트랜잭션 없음
    public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
        // 1. 재고 차감
        product.decreaseStock(quantity);
        productRepository.save(product);  // 커밋
        
        // 2. 포인트 차감
        user.deductPoint(amount);
        userRepository.save(user);  // 커밋
        
        // 3. 주문 저장
        orderRepository.save(order);  // 커밋
        
        // → 만약 3번에서 실패하면?
        // → 재고와 포인트는 이미 차감됨 (데이터 불일치!)
    }
}
```

**문제점**:
- ❌ **원자성 보장 안 됨**: 부분 실패 시 데이터 불일치
- ❌ **롤백 불가능**: 이미 커밋된 데이터는 롤백 불가
- ❌ **데이터 정합성 문제**: 재고는 차감되었는데 주문은 생성 안 됨

---

### 2. 데드락 방지 전략

#### ❌ 잘못된 접근: 트랜잭션 최소화로 데드락 방지

**트랜잭션을 최소화하면 데드락이 줄어들까?**
- ❌ **아니요**: 트랜잭션 부재는 데드락을 해결하지 못함
- ❌ **오히려 문제 발생**: 데이터 일관성 문제가 더 심각해짐

#### ✅ 올바른 접근: 트랜잭션 범위 최소화 + 락 순서 일관성

**데드락 방지 전략**:

1. **락 순서 일관성 유지**
   ```java
   // ✅ 올바른 예: 항상 같은 순서로 락 획득
   @Transactional
   public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
       // 1. 항상 User 먼저
       User user = loadUserForUpdate(userId);
       
       // 2. 항상 Product 나중에
       Product product = productRepository.findByIdForUpdate(productId);
       
       // → 모든 트랜잭션이 같은 순서로 락 획득 → 데드락 방지
   }
   ```

2. **트랜잭션 범위 최소화**
   ```java
   // ✅ 올바른 예: 필요한 작업만 트랜잭션에 포함
   @Transactional
   public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
       // 1. 빠르게 락 획득
       User user = loadUserForUpdate(userId);
       Product product = productRepository.findByIdForUpdate(productId);
       
       // 2. 비즈니스 로직 실행 (락 유지)
       product.decreaseStock(quantity);
       user.deductPoint(amount);
       
       // 3. 저장 및 커밋 (락 해제)
       productRepository.save(product);
       userRepository.save(user);
       orderRepository.save(order);
       
       // → 트랜잭션 범위 최소화 (락 유지 시간 단축)
   }
   ```

3. **타임아웃 설정**
   ```java
   @Transactional(timeout = 10)  // 10초 타임아웃
   public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
       // 타임아웃 설정으로 무한 대기 방지
   }
   ```

4. **읽기 전용 트랜잭션 명시**
   ```java
   @Transactional(readOnly = true)  // 읽기 전용 → 락 최소화
   public List<OrderInfo> getOrders(String userId) {
       // 읽기 작업은 쓰기 락 불필요
   }
   ```

---

## 📊 규모가 큰 서비스에서의 실제 관행

### 1. 트랜잭션 설정 패턴

#### 패턴 1: 애플리케이션 서비스 레이어에서 트랜잭션 관리 (권장) ✅

**특징**:
- 모든 Facade/Service의 public 메서드에 `@Transactional` 설정
- 읽기 전용 메서드는 `readOnly = true` 명시
- 도메인 레이어는 트랜잭션 설정 안 함

**예시**:
```java
@Component
public class PurchasingFacade {
    
    @Transactional  // ✅ 쓰기 작업
    public OrderInfo createOrder(...) { }
    
    @Transactional(readOnly = true)  // ✅ 읽기 작업
    public List<OrderInfo> getOrders(...) { }
}

// 도메인 레이어는 트랜잭션 설정 안 함
public class Order {
    public void complete() { }  // 트랜잭션 없음
}
```

**장점**:
- ✅ 트랜잭션 경계가 명확함
- ✅ 하나의 유즈케이스 단위로 트랜잭션 관리
- ✅ 유지보수 용이

#### 패턴 2: 클래스 레벨 트랜잭션 설정 (선택적)

**특징**:
- 클래스 레벨에 `@Transactional` 설정
- 개별 메서드에서 필요시 오버라이드

**예시**:
```java
@Transactional  // 클래스 레벨 설정
@Component
public class PurchasingFacade {
    
    public OrderInfo createOrder(...) { }  // 클래스 레벨 트랜잭션 상속
    
    @Transactional(readOnly = true)  // 메서드 레벨 오버라이드
    public List<OrderInfo> getOrders(...) { }
}
```

**장점**:
- ✅ 코드 중복 감소
- ✅ 일관성 유지

**단점**:
- ⚠️ 모든 메서드에 트랜잭션이 적용됨 (의도치 않은 경우 문제)

---

### 2. 데드락 방지 체크리스트

#### ✅ 데드락 방지를 위한 체크리스트

- [ ] **락 순서 일관성**: 모든 트랜잭션이 같은 순서로 락 획득
- [ ] **트랜잭션 범위 최소화**: 필요한 작업만 트랜잭션에 포함
- [ ] **타임아웃 설정**: 무한 대기 방지
- [ ] **읽기 전용 명시**: `readOnly = true`로 불필요한 락 방지
- [ ] **인덱스 활용**: Lock 범위 최소화
- [ ] **락 획득 순서 문서화**: 팀 내 일관성 유지

#### ❌ 데드락 발생 가능한 패턴

**패턴 1: 락 순서 불일치**
```java
// ❌ 트랜잭션 1: User → Product
@Transactional
public void method1() {
    User user = loadUserForUpdate(userId);
    Product product = productRepository.findByIdForUpdate(productId);
}

// ❌ 트랜잭션 2: Product → User (순서 불일치!)
@Transactional
public void method2() {
    Product product = productRepository.findByIdForUpdate(productId);
    User user = loadUserForUpdate(userId);
    // → 데드락 발생 가능!
}
```

**패턴 2: 트랜잭션 범위 과다**
```java
// ❌ 트랜잭션 범위가 너무 넓음
@Transactional
public OrderInfo createOrder(...) {
    // 1. 외부 API 호출 (느림)
    externalApi.call();  // 5초 소요
    
    // 2. DB 작업
    productRepository.save(product);
    
    // → 락이 5초 이상 유지됨 (데드락 위험 증가)
}
```

**패턴 3: 타임아웃 미설정**
```java
// ❌ 타임아웃 없음
@Transactional  // 타임아웃 없음
public OrderInfo createOrder(...) {
    // 무한 대기 가능
}
```

---

## 🎯 현재 프로젝트의 트랜잭션 관리 평가

### 현재 프로젝트의 트랜잭션 설정 현황

#### ✅ 잘 적용된 부분

1. **모든 Facade 메서드에 트랜잭션 설정**
   ```java
   @Component
   public class PurchasingFacade {
       @Transactional  // ✅ 쓰기 작업
       public OrderInfo createOrder(...) { }
       
       @Transactional(readOnly = true)  // ✅ 읽기 작업
       public List<OrderInfo> getOrders(...) { }
   }
   ```

2. **읽기 전용 트랜잭션 명시**
   - `getOrders()`, `getOrder()`, `getLikedProducts()` 모두 `readOnly = true` 설정 ✅

3. **트랜잭션 범위 적절**
   - 하나의 유즈케이스 단위로 트랜잭션 묶음 ✅

4. **락 순서 일관성**
   - `createOrder()`: 항상 User → Product 순서로 락 획득 ✅

#### ⚠️ 개선 가능한 부분

1. **타임아웃 설정 추가 (선택적)**
   ```java
   @Transactional(timeout = 10)  // 타임아웃 추가 고려
   public OrderInfo createOrder(...) { }
   ```

2. **락 순서 문서화**
   - 팀 내 락 획득 순서 가이드라인 문서화

---

## 📊 비교표: 트랜잭션 최소화 vs 엄격한 설정

| 항목 | 트랜잭션 최소화 | 엄격한 트랜잭션 설정 |
|------|--------------|------------------|
| **데이터 일관성** | ❌ 보장 안 됨 | ✅ 보장 |
| **원자성 보장** | ❌ 부분 실패 가능 | ✅ All-or-Nothing |
| **데드락 방지** | ❌ 해결 안 됨 | ✅ 락 순서 일관성으로 해결 |
| **유지보수성** | ❌ 트랜잭션 경계 불명확 | ✅ 트랜잭션 경계 명확 |
| **Spring 권장 사항** | ❌ 비권장 | ✅ 권장 |
| **규모가 큰 서비스** | ❌ 사용 안 함 | ✅ 일반적으로 사용 |

---

## 💡 실무 권장 사항

### 1. 트랜잭션 설정 원칙

#### ✅ DO: 엄격하게 트랜잭션 설정

1. **애플리케이션 서비스 레이어의 모든 public 메서드에 `@Transactional` 설정**
2. **읽기 전용 메서드는 `@Transactional(readOnly = true)` 명시**
3. **하나의 유즈케이스 단위로 트랜잭션 묶음**

#### ❌ DON'T: 트랜잭션 최소화

1. **트랜잭션을 아예 안 쓰는 것은 권장하지 않음**
2. **데드락 방지를 위해 트랜잭션을 제거하는 것은 잘못된 접근**

### 2. 데드락 방지 전략

#### ✅ DO: 트랜잭션 범위 최소화 + 락 순서 일관성

1. **락 순서 일관성 유지**: 모든 트랜잭션이 같은 순서로 락 획득
2. **트랜잭션 범위 최소화**: 필요한 작업만 트랜잭션에 포함
3. **타임아웃 설정**: 무한 대기 방지
4. **읽기 전용 명시**: `readOnly = true`로 불필요한 락 방지

#### ❌ DON'T: 트랜잭션 제거로 데드락 방지

1. **트랜잭션을 제거하면 데이터 일관성 문제 발생**
2. **데드락은 트랜잭션 부재로 해결되지 않음**

---

## 🔍 규모가 큰 서비스에서의 실제 사례

### 사례 1: 대형 이커머스 서비스

**트랜잭션 설정 패턴**:
- ✅ 모든 애플리케이션 서비스 메서드에 `@Transactional` 설정
- ✅ 읽기 전용 메서드는 `readOnly = true` 명시
- ✅ 데드락 방지를 위해 락 순서 가이드라인 문서화
- ✅ 타임아웃 설정 (10-30초)

**데드락 방지 전략**:
- 락 획득 순서: User → Product → Order (일관성 유지)
- 트랜잭션 범위 최소화: 외부 API 호출은 트랜잭션 밖에서 처리
- 모니터링: 데드락 발생 시 알림 및 로그 분석

### 사례 2: 금융 서비스

**트랜잭션 설정 패턴**:
- ✅ 모든 애플리케이션 서비스 메서드에 `@Transactional` 설정
- ✅ 읽기 전용 메서드는 `readOnly = true` 명시
- ✅ 타임아웃 설정 (5-10초, 더 엄격)

**데드락 방지 전략**:
- 락 획득 순서: Account → Transaction (일관성 유지)
- 트랜잭션 범위 최소화: 비즈니스 로직만 포함
- 모니터링: 실시간 데드락 모니터링 및 알림

---

## 📋 종합 체크리스트

### 트랜잭션 설정 체크리스트

- [x] 모든 애플리케이션 서비스 메서드에 `@Transactional` 설정 ✅
- [x] 읽기 전용 메서드는 `@Transactional(readOnly = true)` 명시 ✅
- [x] 하나의 유즈케이스 단위로 트랜잭션 묶음 ✅
- [ ] 타임아웃 설정 고려 (선택적)
- [ ] 락 순서 일관성 문서화

### 데드락 방지 체크리스트

- [x] 락 순서 일관성 유지 ✅
- [x] 트랜잭션 범위 최소화 ✅
- [x] 읽기 전용 트랜잭션 명시 ✅
- [x] 인덱스 활용 (Lock 범위 최소화) ✅
- [ ] 타임아웃 설정 (선택적)

---

## 🎯 결론

### 규모가 큰 서비스에서의 일반적인 관행

1. **✅ 엄격하게 트랜잭션을 설정하는 것이 일반적**
   - 모든 애플리케이션 서비스 메서드에 `@Transactional` 설정
   - 읽기 전용 메서드는 `readOnly = true` 명시

2. **✅ 데드락은 트랜잭션 제거로 해결하지 않음**
   - 트랜잭션 범위 최소화 + 락 순서 일관성으로 해결
   - 타임아웃 설정으로 무한 대기 방지

3. **✅ 현재 프로젝트는 올바른 패턴을 따르고 있음**
   - 모든 Facade 메서드에 트랜잭션 설정 ✅
   - 읽기 전용 트랜잭션 명시 ✅
   - 트랜잭션 범위 적절 ✅
   - 락 순서 일관성 유지 ✅

### 권장 사항

- **트랜잭션을 최소화하지 말고, 엄격하게 설정하되 범위를 최소화하라**
- **데드락 방지는 트랜잭션 제거가 아니라 락 순서 일관성과 범위 최소화로 해결하라**

---

## 🔗 관련 문서

- [동시성 처리 설계 원칙](./15-concurrency-design-principles.md)
- [설계 결정 트레이드오프](./16-design-decision-tradeoffs.md)
- [WAL 성능 평가](./14-wal-performance-evaluation.md)
- [락 설계 평가](./13-lock-design-evaluation.md)

