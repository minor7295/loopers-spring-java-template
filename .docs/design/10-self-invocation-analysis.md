# Self-Invocation 문제 점검

## 📌 개요

Spring의 `@Transactional`은 AOP 프록시를 통해 동작합니다. 같은 클래스 내에서 메서드를 직접 호출하면 프록시를 거치지 않아 트랜잭션이 적용되지 않는 문제가 발생할 수 있습니다.

이 문서는 프로젝트 내에서 self-invocation 문제가 있는지 점검한 결과를 정리합니다.

---

## 🔍 Self-Invocation 문제란?

### 문제 상황

```java
@Component
public class MyService {
    
    @Transactional
    public void methodA() {
        // 같은 클래스의 다른 @Transactional 메서드를 직접 호출
        this.methodB();  // ❌ 프록시를 거치지 않음!
    }
    
    @Transactional
    public void methodB() {
        // 트랜잭션이 적용되지 않음!
    }
}
```

**문제점**:
- `methodA()`에서 `this.methodB()`를 호출하면 프록시를 거치지 않음
- `methodB()`의 `@Transactional`이 적용되지 않음
- 트랜잭션이 시작되지 않아 데이터 일관성 문제 발생 가능

### 해결 방법

1. **Self-Injection 사용** (권장)
   ```java
   @Component
   public class MyService {
       @Autowired
       private MyService self;  // 자기 자신을 주입
       
       @Transactional
       public void methodA() {
           self.methodB();  // ✅ 프록시를 거쳐서 호출
       }
       
       @Transactional
       public void methodB() {
           // 트랜잭션이 정상적으로 적용됨
       }
   }
   ```

2. **별도 서비스로 분리**
   ```java
   @Component
   public class ServiceA {
       private final ServiceB serviceB;
       
       @Transactional
       public void methodA() {
           serviceB.methodB();  // ✅ 다른 서비스를 통해 호출
       }
   }
   ```

---

## ✅ 프로젝트 점검 결과

### 1. PurchasingFacade

#### 점검 대상 메서드

| 메서드 | @Transactional | 내부 메서드 호출 | Self-Invocation 문제 |
|--------|---------------|-----------------|---------------------|
| `createOrder()` | ✅ | `loadUserForUpdate()`, `decreaseStocksForOrderItems()`, `deductUserPoint()` | ✅ 없음 |
| `cancelOrder()` | ✅ | 없음 | ✅ 없음 |
| `getOrders()` | ✅ | `loadUser()` | ✅ 없음 |
| `getOrder()` | ✅ | `loadUser()` | ✅ 없음 |

#### 분석

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // private 메서드 호출
    User user = loadUserForUpdate(userId);  // ✅ 문제 없음
    decreaseStocksForOrderItems(order.getItems(), products);  // ✅ 문제 없음
    deductUserPoint(user, order.getTotalAmount());  // ✅ 문제 없음
    // ...
}

private User loadUserForUpdate(String userId) { ... }  // @Transactional 없음
private void decreaseStocksForOrderItems(...) { ... }  // @Transactional 없음
private void deductUserPoint(...) { ... }  // @Transactional 없음
```

**결론**: ✅ **문제 없음**
- 호출되는 메서드들(`loadUserForUpdate`, `decreaseStocksForOrderItems`, `deductUserPoint`, `loadUser`)은 모두 `@Transactional`이 없는 private 메서드입니다.
- 같은 트랜잭션 내에서 실행되므로 문제가 없습니다.
- `@Transactional`이 적용된 메서드끼리 직접 호출하는 경우가 없습니다.

### 2. LikeFacade

#### 점검 대상 메서드

| 메서드 | @Transactional | 내부 메서드 호출 | Self-Invocation 문제 |
|--------|---------------|-----------------|---------------------|
| `addLike()` | ✅ | `loadUser()`, `loadProduct()` | ✅ 없음 |
| `removeLike()` | ✅ | `loadUser()`, `loadProduct()` | ✅ 없음 |
| `getLikedProducts()` | ❌ | `loadUser()` | ✅ 없음 |

#### 분석

```java
@Transactional
public void addLike(String userId, Long productId) {
    // private 메서드 호출
    User user = loadUser(userId);  // ✅ 문제 없음
    loadProduct(productId);  // ✅ 문제 없음
    // ...
}

private User loadUser(String userId) { ... }  // @Transactional 없음
private Product loadProduct(Long productId) { ... }  // @Transactional 없음
```

**결론**: ✅ **문제 없음**
- 호출되는 메서드들(`loadUser`, `loadProduct`)은 모두 `@Transactional`이 없는 private 메서드입니다.
- 같은 트랜잭션 내에서 실행되므로 문제가 없습니다.

### 3. PointWalletFacade

#### 점검 대상 메서드

| 메서드 | @Transactional | 내부 메서드 호출 | Self-Invocation 문제 |
|--------|---------------|-----------------|---------------------|
| `chargePoint()` | ✅ | 없음 | ✅ 없음 |
| `getPoints()` | ❌ | 없음 | ✅ 없음 |

#### 분석

```java
@Transactional
public PointsInfo chargePoint(String userId, Long amount) {
    // 다른 메서드 호출 없음
    User user = userRepository.findByUserId(userId);
    // ...
}
```

**결론**: ✅ **문제 없음**
- 다른 메서드를 호출하지 않으므로 문제가 없습니다.

### 4. SignUpFacade

#### 점검 대상 메서드

| 메서드 | @Transactional | 내부 메서드 호출 | Self-Invocation 문제 |
|--------|---------------|-----------------|---------------------|
| `signUp()` | ✅ | `parseGender()` | ✅ 없음 |

#### 분석

```java
@Transactional
public SignUpInfo signUp(String userId, String email, String birthDateStr, String genderStr) {
    Gender gender = parseGender(genderStr);  // ✅ 문제 없음
    // ...
}

private Gender parseGender(String genderStr) { ... }  // @Transactional 없음
```

**결론**: ✅ **문제 없음**
- `parseGender()`는 `@Transactional`이 없는 private 메서드입니다.
- 단순 변환 로직이므로 문제가 없습니다.

---

## 📊 전체 점검 결과 요약

| 클래스 | @Transactional 메서드 수 | Self-Invocation 문제 | 비고 |
|--------|-------------------------|-------------------|------|
| **PurchasingFacade** | 4개 | ✅ 없음 | private 메서드만 호출 |
| **LikeFacade** | 2개 | ✅ 없음 | private 메서드만 호출 |
| **PointWalletFacade** | 1개 | ✅ 없음 | 내부 메서드 호출 없음 |
| **SignUpFacade** | 1개 | ✅ 없음 | private 메서드만 호출 |

**전체 결론**: ✅ **Self-Invocation 문제 없음**

---

## 🔍 점검 기준

### 문제가 되는 경우

```java
@Component
public class ProblematicService {
    
    @Transactional
    public void methodA() {
        this.methodB();  // ❌ 문제: 같은 클래스의 @Transactional 메서드 직접 호출
    }
    
    @Transactional  // 이 트랜잭션이 적용되지 않음!
    public void methodB() {
        // ...
    }
}
```

### 문제가 되지 않는 경우

#### 1. Private 메서드 호출 (현재 프로젝트의 경우)

```java
@Component
public class SafeService {
    
    @Transactional
    public void methodA() {
        this.helperMethod();  // ✅ 문제 없음: @Transactional 없는 private 메서드
    }
    
    private void helperMethod() {  // @Transactional 없음
        // 같은 트랜잭션 내에서 실행됨
    }
}
```

#### 2. 다른 서비스를 통한 호출

```java
@Component
public class SafeService {
    private final OtherService otherService;
    
    @Transactional
    public void methodA() {
        otherService.methodB();  // ✅ 문제 없음: 다른 서비스를 통해 호출
    }
}
```

#### 3. Self-Injection 사용

```java
@Component
public class SafeService {
    @Autowired
    private SafeService self;  // 자기 자신을 주입
    
    @Transactional
    public void methodA() {
        self.methodB();  // ✅ 문제 없음: 프록시를 거쳐서 호출
    }
    
    @Transactional
    public void methodB() {
        // 트랜잭션이 정상적으로 적용됨
    }
}
```

---

## 💡 권장 사항

### 현재 프로젝트는 문제 없음

현재 프로젝트에서는:
1. ✅ `@Transactional` 메서드가 다른 `@Transactional` 메서드를 직접 호출하지 않음
2. ✅ Private 메서드만 호출하므로 같은 트랜잭션 내에서 실행됨
3. ✅ Self-Invocation 문제가 발생할 수 있는 패턴이 없음

### 향후 주의사항

새로운 코드를 작성할 때:
1. ⚠️ 같은 클래스 내에서 `@Transactional` 메서드를 직접 호출하지 않기
2. ⚠️ 필요하다면 Self-Injection 또는 별도 서비스로 분리
3. ✅ Private 메서드는 문제 없으므로 자유롭게 사용 가능

---

## 🔗 참고 자료

- Spring AOP Proxy: https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#aop-understanding-aop-proxies
- Self-Invocation Problem: https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#aop-understanding-aop-proxies

