# OrderStatusUpdater 계층 위치 분석

## 현재 문제점

### 1. ❌ DIP (의존성 역전 원칙) 위반

**현재 코드:**
```java
@Component
public class OrderStatusUpdater {
    private final UserJpaRepository userJpaRepository; // ❌ 인프라 구현체 직접 의존
}
```

**문제:**
- `UserJpaRepository`는 인프라 계층의 구현체입니다
- 도메인 서비스가 인프라 구현체에 직접 의존하면:
  - 도메인 계층이 인프라 계층에 의존하게 됨 (의존성 방향 위반)
  - 테스트 시 인프라 구현체를 Mock하기 어려움
  - 인프라 변경 시 도메인 서비스도 수정 필요

### 2. ⚠️ 트랜잭션 관리 위치

**현재 코드:**
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void updateByPaymentStatus(...) {
    // ...
}
```

**분석:**
- 트랜잭션 관리는 기술적 관심사이지만
- 도메인 서비스에서 트랜잭션을 관리하는 것도 일반적입니다
- 예: `UserService`, `ProductDetailService` 등도 `@Transactional` 사용

### 3. ⚠️ 외부 시스템 DTO 사용

**현재 코드:**
```java
public void updateByPaymentStatus(
    Long orderId,
    PaymentGatewayDto.TransactionStatus status, // ⚠️ 인프라 DTO
    String transactionKey,
    String reason
)
```

**분석:**
- `PaymentGatewayDto.TransactionStatus`는 인프라 계층의 DTO입니다
- 하지만 이것은 외부 시스템의 도메인 개념이므로 어느 정도 허용 가능합니다
- 더 나은 방법: 도메인 모델로 변환 (예: `PaymentStatus` enum)

## 판단 기준

### 도메인 서비스 vs 애플리케이션 서비스

| 기준 | 도메인 서비스 | 애플리케이션 서비스 |
|------|-------------|-------------------|
| **책임** | 도메인 로직 (비즈니스 규칙) | 유스케이스 조율 |
| **의존성** | 도메인 리포지토리 인터페이스만 | 도메인 서비스 + 인프라 어댑터 |
| **트랜잭션** | 가능 (도메인 트랜잭션) | 가능 (유스케이스 트랜잭션) |
| **재사용성** | 여러 애플리케이션 서비스에서 사용 | 특정 유스케이스 전용 |

### OrderStatusUpdater 분석

**현재 책임:**
- ✅ 결제 상태에 따른 주문 상태 업데이트 (도메인 로직)
- ✅ 주문 완료/취소 처리 (도메인 로직)
- ✅ 이미 처리된 주문 건너뛰기 (도메인 로직)

**의존성:**
- ✅ `OrderRepository` (도메인 리포지토리 인터페이스)
- ❌ `UserJpaRepository` (인프라 구현체) - **문제**
- ✅ `OrderCancellationService` (도메인 서비스)

**결론:**
- **도메인 서비스로 유지하는 것이 맞습니다**
- 하지만 `UserJpaRepository` 대신 `UserRepository`를 사용해야 합니다

## 해결 방안

### 1. ✅ UserRepository에 findById 메서드 추가

**문제:**
- `Order.getUserId()`는 `Long` (DB PK)을 반환
- `UserRepository.findByUserId()`는 `String userId` (비즈니스 식별자)를 받음
- 따라서 `UserJpaRepository.findById(Long id)`를 사용해야 하는 상황

**해결:**
```java
// UserRepository 인터페이스에 추가
public interface UserRepository {
    // 기존 메서드들...
    
    /**
     * 사용자 ID (PK)로 사용자를 조회합니다.
     *
     * @param id 사용자 ID (PK)
     * @return 조회된 사용자, 없으면 null
     */
    User findById(Long id);
}
```

### 2. ✅ OrderStatusUpdater 수정

```java
@Component
public class OrderStatusUpdater {
    private final OrderRepository orderRepository;
    private final UserRepository userRepository; // ✅ 도메인 리포지토리 인터페이스 사용
    private final OrderCancellationService orderCancellationService;
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateByPaymentStatus(...) {
        // ...
        User user = userRepository.findById(order.getUserId()); // ✅ 수정
        // ...
    }
}
```

### 3. ⚠️ (선택사항) PaymentStatus 도메인 모델 생성

더 나은 방법은 외부 시스템 DTO를 도메인 모델로 변환하는 것입니다:

```java
// 도메인 모델
public enum PaymentStatus {
    SUCCESS,
    FAILED,
    PENDING
}

// OrderStatusUpdater
public void updateByPaymentStatus(
    Long orderId,
    PaymentStatus status, // ✅ 도메인 모델 사용
    String transactionKey,
    String reason
) {
    // ...
}
```

## 최종 판단

### ✅ 도메인 서비스로 유지

**이유:**
1. **도메인 로직**: 결제 상태에 따른 주문 상태 업데이트는 도메인 로직입니다
2. **재사용성**: 여러 애플리케이션 서비스에서 사용 가능합니다
3. **책임 분리**: 주문 상태 업데이트 로직이 한 곳에 집중됩니다

### ❌ 애플리케이션 서비스로 이동하지 않음

**이유:**
1. **도메인 지식**: 주문 상태 업데이트는 도메인 지식입니다
2. **재사용성 저하**: 애플리케이션 서비스로 이동하면 재사용이 어려워집니다
3. **책임 혼재**: 애플리케이션 서비스에 도메인 로직이 섞이게 됩니다

### 🔧 수정 필요 사항

1. **UserRepository에 findById 추가**
2. **OrderStatusUpdater에서 UserJpaRepository 제거**
3. **UserRepository 사용하도록 변경**

## 참고: 다른 도메인 서비스 예시

### UserService (도메인 서비스)
```java
@Component
public class UserService {
    private final UserRepository userRepository; // ✅ 도메인 리포지토리 인터페이스
    
    @Transactional
    public User create(...) {
        // 도메인 로직
    }
}
```

### ProductDetailService (도메인 서비스)
```java
@Component
public class ProductDetailService {
    // Repository 의존성 없음
    // 도메인 객체를 파라미터로 받아 처리
    public ProductDetail combineProductAndBrand(...) {
        // 도메인 로직
    }
}
```

**공통점:**
- 모두 도메인 리포지토리 인터페이스만 사용
- 인프라 구현체에 직접 의존하지 않음

