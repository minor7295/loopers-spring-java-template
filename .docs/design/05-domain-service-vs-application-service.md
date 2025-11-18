# 05-domain-service-vs-application-service.md
> 루프팩 감성 이커머스 – 도메인 서비스 vs 애플리케이션 서비스 개념 차이

---

## 🎯 개요

본 문서는 **도메인 서비스(Domain Service)**와 **애플리케이션 서비스(Application Service, Facade)**의 개념적 차이와 역할을 명확히 설명합니다.

> ⚠️ **중요**: Spring의 `@Service` 어노테이션과 DDD의 "도메인 서비스"는 **다른 개념**입니다.

---

## 📋 개념 정의

### 1️⃣ 도메인 서비스 (Domain Service)

**도메인 서비스**는 **상태가 없는(stateless)** 순수한 도메인 로직을 수행하는 서비스입니다.

#### 특징

- **상태 없음 (Stateless)**: 인스턴스 변수를 가지지 않음
- **도메인 객체 협력**: 여러 도메인 객체 간의 협력 로직 처리
- **비즈니스 중심**: 순수한 비즈니스 규칙만 처리
- **인프라 독립**: Repository나 외부 시스템에 의존하지 않음
- **재사용성**: 여러 애플리케이션 서비스에서 재사용 가능

#### 예시

```java
/**
 * 포인트 할인 계산 도메인 서비스
 * 
 * 상태가 없고, 순수한 비즈니스 로직만 처리합니다.
 */
@Component
public class PointDiscountCalculator {
    
    /**
     * VIP 등급에 따른 포인트 할인율 계산
     * 
     * @param userLevel 사용자 등급
     * @param originalAmount 원래 금액
     * @return 할인된 금액
     */
    public Long calculateDiscount(UserLevel userLevel, Long originalAmount) {
        // 순수한 비즈니스 로직만 처리
        double discountRate = switch (userLevel) {
            case VIP -> 0.1;      // 10% 할인
            case GOLD -> 0.05;    // 5% 할인
            case SILVER -> 0.02;  // 2% 할인
            default -> 0.0;
        };
        return (long) (originalAmount * discountRate);
    }
    
    /**
     * 포인트 적립률 계산
     * 
     * @param purchaseAmount 구매 금액
     * @return 적립될 포인트
     */
    public Long calculateRewardPoints(Long purchaseAmount) {
        // 구매 금액의 1% 적립
        return purchaseAmount / 100;
    }
}
```

#### 언제 사용하는가?

- **여러 엔티티 간의 협력 로직**: 하나의 엔티티에 속하지 않는 비즈니스 규칙
- **복잡한 계산 로직**: 할인 계산, 포인트 계산 등
- **도메인 규칙 검증**: 여러 엔티티를 고려한 비즈니스 규칙 검증

---

### 2️⃣ 애플리케이션 서비스 (Application Service / Facade)

**애플리케이션 서비스**는 **유즈케이스를 조합하고 흐름을 제어**하는 서비스입니다.

#### 특징

- **유즈케이스 조합**: 여러 도메인 서비스를 조합하여 하나의 유즈케이스 완성
- **흐름 제어**: 비즈니스 프로세스의 순서와 흐름 관리
- **인프라 의존 가능**: Repository, 외부 시스템 등에 의존 가능
- **절차 중심**: 단계별로 처리하는 절차적 로직
- **트랜잭션 관리**: 유즈케이스 전체를 하나의 트랜잭션으로 관리

#### 예시: 현재 프로젝트의 `UserService`와 `PointService`

현재 프로젝트의 `UserService`와 `PointService`는 실제로는 **애플리케이션 서비스**에 가깝습니다:

```java
/**
 * 사용자 애플리케이션 서비스
 * 
 * Repository에 의존하고, 데이터 저장/조회 등의 절차적 로직을 처리합니다.
 */
@Component
public class UserService {
    private final UserRepository userRepository;  // 인프라 의존

    public User create(String userId, String email, String birthDateStr, Gender gender) {
        // 1. 도메인 객체 생성
        User user = User.of(userId, email, birthDateStr, gender);
        
        // 2. 저장 (인프라 작업)
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 3. 예외 처리 (절차적 로직)
            throw new CoreException(ErrorType.CONFLICT, "이미 가입된 ID입니다: " + userId);
        }
    }
}
```

```java
/**
 * 포인트 애플리케이션 서비스
 * 
 * Repository에 의존하고, 트랜잭션을 관리합니다.
 */
@Component
public class PointService {
    private final PointRepository pointRepository;  // 인프라 의존

    @Transactional  // 트랜잭션 관리
    public Point charge(String userId, Long amount) {
        // 1. 조회 (인프라 작업)
        Point point = pointRepository.findByUserId(userId);
        if (point == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "포인트를 찾을 수 없습니다.");
        }
        
        // 2. 도메인 객체의 메서드 호출
        point.charge(amount);
        
        // 3. 저장 (인프라 작업)
        return pointRepository.save(point);
    }
}
```

#### 예시: `SignUpFacade` (여러 애플리케이션 서비스 조율)

```java
/**
 * 회원가입 Facade
 * 
 * 여러 애플리케이션 서비스를 조합하여 회원가입 유즈케이스를 완성합니다.
 */
@Component
public class SignUpFacade {
    private final UserService userService;      // 애플리케이션 서비스
    private final PointService pointService;    // 애플리케이션 서비스

    @Transactional  // 유즈케이스 전체를 하나의 트랜잭션으로 관리
    public SignUpInfo signUp(String userId, String email, String birthDateStr, Gender gender) {
        // 1. 사용자 생성
        User user = userService.create(userId, email, birthDateStr, gender);
        
        // 2. 포인트 초기화
        pointService.create(user, 0L);
        
        // 3. 결과 조합
        return SignUpInfo.from(user);
    }
}
```

---

## 🔍 주요 차이점

| 구분 | 도메인 서비스 (Domain Service) | 애플리케이션 서비스 (Application Service) |
|------|------------------------------|------------------------------------------|
| **목적** | 도메인 로직 수행 (상태 없음) | 유즈케이스 조합, 흐름 제어 |
| **상태** | Stateless (상태 없음) | Stateful 가능 (Repository 등 의존) |
| **의존성** | 도메인 객체만 의존 | Repository, 외부 시스템 등 인프라 의존 가능 |
| **책임** | 도메인 객체 협력, 비즈니스 중심 | 절차 중심, 흐름 제어 |
| **트랜잭션** | 트랜잭션 관리하지 않음 | 트랜잭션 경계 관리 |
| **테스트** | 단위 테스트로 충분 | 통합 테스트 중심 |
| **예시** | 포인트 계산, 할인 계산, 재고 차감 로직 | 주문 생성, 결제 처리, 회원가입 |
| **재사용성** | 여러 애플리케이션 서비스에서 재사용 | 특정 유즈케이스에 특화 |

---

## 💡 실제 사용 예시

### 시나리오: 주문 생성

#### 1. 도메인 서비스: 할인 계산 로직

```java
/**
 * 주문 할인 계산 도메인 서비스
 * 
 * 상태가 없고, 순수한 비즈니스 로직만 처리합니다.
 */
@Component
public class OrderDiscountCalculator {
    
    /**
     * VIP 등급에 따른 할인 금액 계산
     */
    public Long calculateDiscount(UserLevel userLevel, Long totalAmount) {
        double discountRate = switch (userLevel) {
            case VIP -> 0.1;
            case GOLD -> 0.05;
            default -> 0.0;
        };
        return (long) (totalAmount * discountRate);
    }
}
```

#### 2. 애플리케이션 서비스: 주문 생성 유즈케이스

```java
/**
 * 주문 애플리케이션 서비스
 * 
 * 여러 도메인 서비스를 조합하여 주문 생성 유즈케이스를 처리합니다.
 */
@Component
public class OrderService {
    private final OrderRepository orderRepository;
    private final PointRepository pointRepository;
    private final ProductRepository productRepository;
    private final OrderDiscountCalculator discountCalculator;  // 도메인 서비스

    @Transactional
    public Order createOrder(String userId, List<OrderItem> items) {
        // 1. 사용자 조회
        User user = userRepository.findByUserId(userId);
        
        // 2. 상품 재고 확인
        for (OrderItem item : items) {
            Product product = productRepository.findById(item.getProductId());
            if (product.getStock() < item.getQuantity()) {
                throw new CoreException(ErrorType.BAD_REQUEST, "재고가 부족합니다.");
            }
        }
        
        // 3. 총액 계산
        Long totalAmount = items.stream()
            .mapToLong(item -> item.getPrice() * item.getQuantity())
            .sum();
        
        // 4. 할인 계산 (도메인 서비스 사용)
        Long discountAmount = discountCalculator.calculateDiscount(user.getLevel(), totalAmount);
        Long finalAmount = totalAmount - discountAmount;
        
        // 5. 포인트 확인 및 차감
        Point point = pointRepository.findByUserId(userId);
        if (point.getBalance() < finalAmount) {
            throw new CoreException(ErrorType.BAD_REQUEST, "포인트가 부족합니다.");
        }
        point.deduct(finalAmount);
        
        // 6. 재고 차감
        for (OrderItem item : items) {
            Product product = productRepository.findById(item.getProductId());
            product.decreaseStock(item.getQuantity());
        }
        
        // 7. 주문 생성
        Order order = Order.create(user, items, finalAmount);
        return orderRepository.save(order);
    }
}
```

---

## ⚠️ 중요한 인사이트

> **"도메인 서비스가 많다는 건, 도메인 자체를 잘 설계하지 못했다는 신호다."**

### 왜 그런가?

1. **엔티티에 로직이 있어야 함**: 대부분의 비즈니스 로직은 엔티티의 메서드로 처리되어야 합니다.
   ```java
   // ✅ 좋은 예: 엔티티에 로직이 있음
   public class Point {
       public void charge(Long amount) {
           validateChargeAmount(amount);
           this.balance += amount;
       }
   }
   
   // ❌ 나쁜 예: 도메인 서비스에 로직이 있음
   public class PointService {
       public void charge(Point point, Long amount) {
           // 로직이 서비스에 있음
       }
   }
   ```

2. **도메인 서비스는 예외적인 경우에만 사용**: 여러 엔티티 간의 협력이 필요한 경우에만 사용합니다.

3. **현재 프로젝트의 문제점**: `UserService`, `PointService`는 실제로는 애플리케이션 서비스 역할을 하고 있으며, 많은 로직이 엔티티가 아닌 서비스에 있습니다.

---

## 🏗️ 아키텍처 계층 구조

```
┌─────────────────────────────────────┐
│   Controller (인터페이스 계층)      │
│   - HTTP 요청/응답 처리              │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   Application Service (Facade)       │
│   - 유즈케이스 조합, 흐름 제어       │
│   - 트랜잭션 경계 관리                │
│   - 인프라 의존 (Repository 등)      │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   Domain Service (도메인 계층)       │
│   - 상태 없는 도메인 로직             │
│   - 도메인 객체 협력                  │
│   - 인프라 독립                       │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   Entity (도메인 계층)               │
│   - 대부분의 비즈니스 로직            │
│   - 상태 관리                         │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   Repository (인프라 계층)           │
│   - 데이터 영속성 처리                │
└─────────────────────────────────────┘
```

---

## 📝 요약

### 도메인 서비스 (Domain Service)

- **상태 없음**: Stateless
- **도메인 로직**: 순수한 비즈니스 규칙만 처리
- **인프라 독립**: Repository에 의존하지 않음
- **재사용성**: 여러 애플리케이션 서비스에서 재사용
- **예시**: 할인 계산, 포인트 계산, 재고 차감 로직

### 애플리케이션 서비스 (Application Service / Facade)

- **유즈케이스 조합**: 여러 도메인 서비스를 조합
- **흐름 제어**: 비즈니스 프로세스의 순서 관리
- **인프라 의존**: Repository, 외부 시스템 등에 의존
- **트랜잭션 관리**: 유즈케이스 전체를 하나의 트랜잭션으로 관리
- **예시**: 주문 생성, 결제 처리, 회원가입

### 현재 프로젝트의 구조

- `UserService`, `PointService`: **애플리케이션 서비스** (Repository 의존, 트랜잭션 관리)
- `SignUpFacade`: **애플리케이션 서비스** (여러 애플리케이션 서비스 조율)
- **도메인 서비스 예시**: 현재 프로젝트에는 명확한 도메인 서비스가 없음 (대부분의 로직이 엔티티나 애플리케이션 서비스에 있음)

---

## 🔗 관련 문서

- [01-requirements.md](./01-requirements.md): 요구사항 및 유스케이스
- [02-sequence-diagrams.md](./02-sequence-diagrams.md): 시퀀스 다이어그램
- [03-class-diagram.md](./03-class-diagram.md): 클래스 다이어그램




