# 트랜잭션 전파(Propagation) 방식 점검

## 📌 개요

본 문서는 프로젝트의 트랜잭션 전파 방식 사용 현황을 점검하고, 필요한 경우 보완 방안을 제시합니다.

---

## 🎯 트랜잭션 전파 방식 개요

### 전파 방식 종류

| 전파 방식 | 설명 | 사용 시나리오 |
|----------|------|-------------|
| **REQUIRED** (default) | 기존 트랜잭션이 있으면 참여, 없으면 새로 생성 | ✅ 대부분의 경우 (기본값) |
| **REQUIRES_NEW** | 기존 트랜잭션을 잠시 중단하고 새 트랜잭션 생성 | 로그 기록, 독립적 작업 |
| **NESTED** | 부모 트랜잭션 내에서 저장점(Savepoint)을 두고 하위 트랜잭션 생성 | 부분 롤백이 필요한 경우 |
| **SUPPORTS** | 기존 트랜잭션이 있으면 참여, 없으면 트랜잭션 없이 실행 | 트랜잭션 선택적 |
| **NOT_SUPPORTED** | 기존 트랜잭션이 있어도 일시 중단하고 트랜잭션 없이 실행 | 트랜잭션 비활성화 |
| **MANDATORY** | 기존 트랜잭션이 반드시 있어야 함, 없으면 예외 발생 | 트랜잭션 필수 |
| **NEVER** | 트랜잭션이 있으면 예외 발생 | 트랜잭션 금지 |

---

## 🔍 현재 프로젝트 점검 결과

### 1. 전파 방식 명시 현황

**확인 결과**: ❌ **전파 방식이 명시된 `@Transactional` 없음**

모든 `@Transactional`은 기본값(REQUIRED) 사용:
```java
@Transactional  // propagation = Propagation.REQUIRED (기본값)
public OrderInfo createOrder(...) { }

@Transactional(readOnly = true)  // propagation = Propagation.REQUIRED (기본값)
public List<OrderInfo> getOrders(...) { }
```

### 2. 중첩 트랜잭션 호출 패턴 분석

#### ✅ Facade 간 중첩 호출 없음

**확인 결과**:
- `PurchasingFacade` → 다른 Facade 호출 없음
- `LikeFacade` → 다른 Facade 호출 없음
- `PointWalletFacade` → 다른 Facade 호출 없음
- `SignUpFacade` → 다른 Facade 호출 없음

**현재 구조**:
```
Controller → Facade → Repository
```

**중첩 호출이 없는 구조**:
```
❌ Controller → Facade A → Facade B (없음)
❌ Facade A → Facade B (없음)
```

#### ✅ 같은 클래스 내 @Transactional 메서드 간 호출 없음

**확인 결과** (Self-Invocation 분석 참고):
- `@Transactional` 메서드가 다른 `@Transactional` 메서드를 직접 호출하지 않음
- Private 메서드만 호출 (같은 트랜잭션 내에서 실행)

**예시**:
```java
@Transactional
public OrderInfo createOrder(...) {
    // ✅ Private 메서드 호출 (같은 트랜잭션)
    User user = loadUserForUpdate(userId);
    decreaseStocksForOrderItems(...);
    // ❌ 다른 @Transactional 메서드 호출 없음
}
```

---

## 📊 전파 방식별 필요성 평가

### ✅ REQUIRED (현재 상태 유지)

**현재 프로젝트**: ✅ **REQUIRED로 충분**

**이유**:
1. **Facade 간 중첩 호출 없음**
   - 각 Facade가 독립적으로 동작
   - 중첩 트랜잭션이 필요한 시나리오 없음

2. **같은 클래스 내 중첩 호출 없음**
   - `@Transactional` 메서드 간 직접 호출 없음
   - Private 메서드는 같은 트랜잭션 내에서 실행

3. **단순한 트랜잭션 구조**
   - Controller → Facade → Repository
   - 복잡한 중첩 구조 없음

**결론**: ✅ **현재 상태 유지 (명시 불필요)**

---

### ⚠️ REQUIRES_NEW (향후 고려)

**필요한 시나리오**:

#### 1. 로그 기록 (독립적 커밋)

**예시: 주문 생성 시 로그 기록**
```java
@Component
public class PurchasingFacade {
    private final OrderLogService orderLogService;
    
    @Transactional
    public OrderInfo createOrder(...) {
        // 주문 생성 로직...
        Order order = orderRepository.save(order);
        
        // ✅ 로그 기록은 독립적으로 커밋되어야 함
        // 주문 생성이 실패해도 로그는 남아야 함
        orderLogService.logOrderCreated(order.getId());  // REQUIRES_NEW 필요
        
        return OrderInfo.from(order);
    }
}

@Component
public class OrderLogService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrderCreated(Long orderId) {
        // 로그 기록 (독립 트랜잭션)
        // 주문 생성이 롤백되어도 로그는 커밋됨
    }
}
```

**현재 프로젝트**: ❌ **해당 시나리오 없음**
- 로그 기록 기능 없음
- 외부 시스템 연동 없음

**향후 추가 시**: ✅ **REQUIRES_NEW 고려**

---

#### 2. 외부 시스템 연동 (독립적 커밋)

**예시: 주문 생성 후 외부 시스템 알림**
```java
@Component
public class PurchasingFacade {
    private final ExternalNotificationService notificationService;
    
    @Transactional
    public OrderInfo createOrder(...) {
        // 주문 생성 로직...
        Order order = orderRepository.save(order);
        
        // ✅ 외부 시스템 알림은 독립적으로 커밋되어야 함
        // 주문 생성이 실패해도 알림은 전송되어야 함 (또는 반대)
        notificationService.notifyOrderCreated(order.getId());  // REQUIRES_NEW 필요
        
        return OrderInfo.from(order);
    }
}
```

**현재 프로젝트**: ❌ **해당 시나리오 없음**
- 외부 시스템 연동 없음

**향후 추가 시**: ✅ **REQUIRES_NEW 고려**

---

### ⚠️ NESTED (향후 고려)

**필요한 시나리오**:

#### 부분 롤백이 필요한 경우

**예시: 여러 상품 주문 시 일부 실패 처리**
```java
@Transactional
public OrderInfo createOrder(...) {
    // 여러 상품 처리
    for (OrderItemCommand command : commands) {
        try {
            // ✅ 각 상품 처리를 NESTED 트랜잭션으로
            processOrderItem(command);  // NESTED 필요
        } catch (Exception e) {
            // 해당 상품만 롤백, 나머지는 계속 진행
            log.error("상품 처리 실패: {}", command.productId(), e);
        }
    }
}

@Transactional(propagation = Propagation.NESTED)
private void processOrderItem(OrderItemCommand command) {
    // 상품별 처리 (부분 롤백 가능)
}
```

**현재 프로젝트**: ❌ **해당 시나리오 없음**
- 전체 주문이 원자적으로 처리되어야 함
- 부분 롤백이 필요한 시나리오 없음

**향후 추가 시**: ⚠️ **드문 경우, 신중히 고려**

---

### ❌ 기타 전파 방식 (불필요)

**SUPPORTS, NOT_SUPPORTED, MANDATORY, NEVER**:
- 현재 프로젝트에서 필요한 시나리오 없음
- 복잡도만 증가시킴

---

## 📋 현재 프로젝트 트랜잭션 구조

### 현재 구조

```
Controller (트랜잭션 없음)
    ↓
Facade (@Transactional - REQUIRED)
    ↓
Repository (트랜잭션 없음)
    ↓
Database
```

**특징**:
- ✅ 단순한 구조
- ✅ 중첩 없음
- ✅ REQUIRED로 충분

### 중첩 호출이 없는 이유

1. **Facade가 독립적**
   - 각 Facade가 완전한 유즈케이스 처리
   - 다른 Facade의 도움 불필요

2. **도메인 경계 명확**
   - 주문, 좋아요, 포인트 등이 독립적
   - 서로 의존하지 않음

3. **단순한 아키텍처**
   - Controller → Facade → Repository
   - 복잡한 서비스 계층 없음

---

## 🎯 권장 사항

### ✅ 현재 상태 유지

**결론**: ✅ **전파 방식 명시 불필요 (현재 상태 유지)**

**이유**:
1. **REQUIRED로 충분**
   - Facade 간 중첩 호출 없음
   - 같은 클래스 내 중첩 호출 없음
   - 단순한 트랜잭션 구조

2. **명시적 지정의 단점**
   - 코드 복잡도 증가
   - 불필요한 명시는 오히려 혼란 야기
   - 기본 동작으로 충분

3. **유지보수성**
   - 기본값 사용 시 Spring의 표준 동작
   - 팀원들이 이해하기 쉬움

---

### ⚠️ 향후 추가 시 고려사항

#### 1. 로그 기록 기능 추가 시

**REQUIRES_NEW 고려**:
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void logOrderCreated(Long orderId) {
    // 로그 기록 (독립 트랜잭션)
}
```

**이유**:
- 주문 생성 실패 시에도 로그는 남아야 함
- 독립적으로 커밋되어야 함

#### 2. 외부 시스템 연동 추가 시

**REQUIRES_NEW 고려**:
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void notifyExternalSystem(Order order) {
    // 외부 시스템 알림 (독립 트랜잭션)
}
```

**이유**:
- 외부 시스템 연동 실패가 주문 생성에 영향을 주지 않아야 함
- 또는 반대로 주문 생성 실패가 알림에 영향을 주지 않아야 함

#### 3. 부분 롤백이 필요한 시나리오 추가 시

**NESTED 고려**:
```java
@Transactional(propagation = Propagation.NESTED)
private void processOrderItem(OrderItemCommand command) {
    // 부분 롤백 가능
}
```

**주의**:
- MySQL은 NESTED를 지원하지 않음 (Savepoint 사용)
- Oracle, PostgreSQL 등에서만 지원
- 드문 경우이므로 신중히 고려

---

## 📊 비교표: 전파 방식 명시 vs 미명시

| 항목 | 전파 방식 미명시 (현재) | 전파 방식 명시 |
|------|---------------------|-------------|
| **코드 간결성** | ✅ 간결 | ⚠️ 복잡 |
| **가독성** | ✅ 명확 (기본 동작) | ⚠️ 명시적이지만 복잡 |
| **유지보수** | ✅ 쉬움 | ⚠️ 예외 추가 시 수정 필요 |
| **현재 프로젝트** | ✅ 적절 | ❌ 불필요 |
| **중첩 호출** | ✅ 없음 | ❌ 불필요 |
| **복잡도** | ✅ 낮음 | ⚠️ 높음 |

---

## 🔍 실무 권장 사항

### 1. 기본 원칙: 명시적이지 않으면 기본 동작 사용

**권장**:
- 중첩 호출이 없는 경우: 전파 방식 명시 안 함
- REQUIRED로 충분한 경우: 명시 안 함

**이유**:
- 코드 간결성
- Spring의 기본 동작 이해
- 불필요한 복잡도 방지

### 2. 예외적인 경우에만 명시

**명시가 필요한 경우**:
1. **로그 기록 (독립적 커밋)**
   ```java
   @Transactional(propagation = Propagation.REQUIRES_NEW)
   public void logOrderCreated(...) { }
   ```

2. **외부 시스템 연동 (독립적 커밋)**
   ```java
   @Transactional(propagation = Propagation.REQUIRES_NEW)
   public void notifyExternalSystem(...) { }
   ```

3. **부분 롤백이 필요한 경우 (드문 경우)**
   ```java
   @Transactional(propagation = Propagation.NESTED)
   private void processOrderItem(...) { }
   ```

### 3. 현재 프로젝트 권장

**✅ 전파 방식 명시하지 않음 (현재 상태 유지)**

**이유**:
- Facade 간 중첩 호출 없음
- 같은 클래스 내 중첩 호출 없음
- REQUIRED 기본값으로 충분
- 코드 간결성 유지

**향후 추가 시**:
- 로그 기록 기능: `REQUIRES_NEW` 고려
- 외부 시스템 연동: `REQUIRES_NEW` 고려
- 부분 롤백 필요: `NESTED` 고려 (드문 경우)

---

## 📊 종합 평가

### 현재 프로젝트

| 항목 | 평가 | 설명 |
|------|------|------|
| **전파 방식 명시** | ❌ 없음 | 기본값(REQUIRED) 사용 |
| **중첩 호출** | ✅ 없음 | Facade 간 호출 없음 |
| **같은 클래스 내 중첩** | ✅ 없음 | @Transactional 메서드 간 호출 없음 |
| **전파 방식 명시 필요** | ❌ 불필요 | REQUIRED로 충분 |
| **권장 사항** | ✅ 현재 상태 유지 | 명시하지 않음 |

### 일반적인 관행

**규모가 큰 서비스에서의 관행**:
- ✅ **대부분 전파 방식 명시 안 함**
- ✅ **REQUIRED 기본값 사용**
- ⚠️ **로그 기록, 외부 연동 시에만 REQUIRES_NEW 명시**

**이유**:
- 코드 간결성
- Spring의 기본 동작 이해
- 불필요한 복잡도 방지

---

## 🔗 관련 문서

- [트랜잭션 관리 관행](./17-transaction-management-practices.md)
- [Self-Invocation 문제 점검](./10-self-invocation-analysis.md)
- [트랜잭션 롤백 범위 분석](./20-transaction-rollback-for-analysis.md)

