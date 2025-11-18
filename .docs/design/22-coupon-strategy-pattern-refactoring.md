# 쿠폰 할인 계산 전략 패턴 리팩토링

## 📌 개요

본 문서는 쿠폰 할인 계산 로직의 OCP(Open-Closed Principle) 위반 문제를 전략 패턴으로 해결한 설계 개선 내용을 정리합니다.

---

## 🎯 문제점: OCP 위반

### 기존 설계의 문제

**기존 코드 (if-else 분기)**:
```java
public Integer calculateDiscountAmount(Integer orderAmount) {
    if (type == CouponType.FIXED_AMOUNT) {
        return Math.min(discountValue, orderAmount);
    } else {
        return (int) Math.round(orderAmount * discountValue / 100.0);
    }
}
```

**문제점**:
- ❌ **OCP 위반**: 새로운 쿠폰 타입 추가 시 기존 코드 수정 필요
- ❌ **분기 폭발**: 쿠폰 타입이 늘어날수록 if-else가 계속 증가
- ❌ **확장성 부족**: 새로운 할인 정책 추가 시 `Coupon` 엔티티 수정 필요
- ❌ **테스트 어려움**: 각 쿠폰 타입별 로직을 독립적으로 테스트하기 어려움

**예시: 새로운 쿠폰 타입 추가 시**:
```java
// ❌ 기존 코드 수정 필요
if (type == CouponType.FIXED_AMOUNT) {
    // ...
} else if (type == CouponType.PERCENTAGE) {
    // ...
} else if (type == CouponType.BUY_ONE_GET_ONE) {  // 새로 추가
    // ...
} else if (type == CouponType.FREE_SHIPPING) {  // 또 추가
    // ...
}
// → 분기가 계속 늘어남!
```

---

## ✅ 해결 방안: 전략 패턴 적용

### 전략 패턴 구조

```
CouponDiscountStrategy (인터페이스)
    ├── FixedAmountDiscountStrategy (정액 쿠폰)
    ├── PercentageDiscountStrategy (정률 쿠폰)
    └── [새로운 전략 추가 가능] (확장 용이)

CouponDiscountStrategyFactory (팩토리)
    └── 쿠폰 타입에 따라 적절한 전략 반환
```

### 개선된 설계

#### 1. 전략 인터페이스

```java
public interface CouponDiscountStrategy {
    Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue);
}
```

#### 2. 구체적인 전략 구현

**정액 쿠폰 전략**:
```java
@Component
public class FixedAmountDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        return Math.min(discountValue, orderAmount);
    }
}
```

**정률 쿠폰 전략**:
```java
@Component
public class PercentageDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        return (int) Math.round(orderAmount * discountValue / 100.0);
    }
}
```

#### 3. 전략 팩토리

```java
@Component
public class CouponDiscountStrategyFactory {
    private final Map<CouponType, CouponDiscountStrategy> strategyMap;

    public CouponDiscountStrategyFactory(
        FixedAmountDiscountStrategy fixedAmountStrategy,
        PercentageDiscountStrategy percentageStrategy
    ) {
        this.strategyMap = Map.of(
            CouponType.FIXED_AMOUNT, fixedAmountStrategy,
            CouponType.PERCENTAGE, percentageStrategy
        );
    }

    public CouponDiscountStrategy getStrategy(CouponType type) {
        CouponDiscountStrategy strategy = strategyMap.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException(
                String.format("지원하지 않는 쿠폰 타입입니다. (타입: %s)", type));
        }
        return strategy;
    }
}
```

#### 4. Coupon 엔티티 개선

```java
public Integer calculateDiscountAmount(Integer orderAmount, CouponDiscountStrategyFactory strategyFactory) {
    if (orderAmount == null || orderAmount <= 0) {
        throw new CoreException(ErrorType.BAD_REQUEST, "주문 금액은 0보다 커야 합니다.");
    }

    // ✅ 전략 패턴 사용: if-else 제거
    CouponDiscountStrategy strategy = strategyFactory.getStrategy(this.type);
    return strategy.calculateDiscountAmount(orderAmount, this.discountValue);
}
```

---

## 📊 개선 효과

### ✅ OCP 준수

**기존 코드 수정 없이 확장 가능**:
```java
// ✅ 새로운 쿠폰 타입 추가 시
@Component
public class BuyOneGetOneDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        // 1+1 쿠폰 로직
        return orderAmount / 2;
    }
}

// Factory에만 추가 (Coupon 엔티티 수정 불필요)
this.strategyMap = Map.of(
    CouponType.FIXED_AMOUNT, fixedAmountStrategy,
    CouponType.PERCENTAGE, percentageStrategy,
    CouponType.BUY_ONE_GET_ONE, buyOneGetOneStrategy  // ✅ 새로 추가
);
```

### ✅ 단일 책임 원칙 (SRP) 준수

- **Coupon 엔티티**: 쿠폰 정보 관리 (할인 계산 로직 제거)
- **전략 클래스**: 각 할인 계산 로직만 담당
- **Factory**: 전략 선택만 담당

### ✅ 테스트 용이성 향상

**각 전략을 독립적으로 테스트 가능**:
```java
@Test
void fixedAmountStrategy_shouldReturnMinValue() {
    FixedAmountDiscountStrategy strategy = new FixedAmountDiscountStrategy();
    Integer result = strategy.calculateDiscountAmount(10_000, 5_000);
    assertThat(result).isEqualTo(5_000);
}

@Test
void percentageStrategy_shouldCalculatePercentage() {
    PercentageDiscountStrategy strategy = new PercentageDiscountStrategy();
    Integer result = strategy.calculateDiscountAmount(10_000, 20);
    assertThat(result).isEqualTo(2_000);
}
```

---

## 🔄 확장 시나리오

### 시나리오 1: 1+1 쿠폰 추가

```java
// 1. CouponType enum에 추가
public enum CouponType {
    FIXED_AMOUNT,
    PERCENTAGE,
    BUY_ONE_GET_ONE  // ✅ 새로 추가
}

// 2. 전략 구현체 생성
@Component
public class BuyOneGetOneDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        // 1+1: 주문 금액의 절반 할인
        return orderAmount / 2;
    }
}

// 3. Factory에 등록 (Coupon 엔티티 수정 불필요!)
public CouponDiscountStrategyFactory(
    FixedAmountDiscountStrategy fixedAmountStrategy,
    PercentageDiscountStrategy percentageStrategy,
    BuyOneGetOneDiscountStrategy buyOneGetOneStrategy  // ✅ 추가
) {
    this.strategyMap = Map.of(
        CouponType.FIXED_AMOUNT, fixedAmountStrategy,
        CouponType.PERCENTAGE, percentageStrategy,
        CouponType.BUY_ONE_GET_ONE, buyOneGetOneStrategy  // ✅ 추가
    );
}
```

**결과**: ✅ `Coupon` 엔티티 수정 없이 확장 완료

---

### 시나리오 2: 무료배송 쿠폰 추가

```java
// 1. CouponType enum에 추가
public enum CouponType {
    FIXED_AMOUNT,
    PERCENTAGE,
    FREE_SHIPPING  // ✅ 새로 추가
}

// 2. 전략 구현체 생성
@Component
public class FreeShippingDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        // 무료배송: 배송비만 할인 (주문 금액에는 영향 없음)
        return discountValue;  // 배송비 금액
    }
}

// 3. Factory에 등록
this.strategyMap = Map.of(
    CouponType.FIXED_AMOUNT, fixedAmountStrategy,
    CouponType.PERCENTAGE, percentageStrategy,
    CouponType.FREE_SHIPPING, freeShippingStrategy  // ✅ 추가
);
```

**결과**: ✅ `Coupon` 엔티티 수정 없이 확장 완료

---

### 시나리오 3: 최소 주문 금액 조건이 있는 쿠폰

```java
// 1. 전략 구현체 생성 (복잡한 로직도 독립적으로 관리)
@Component
public class MinimumOrderAmountDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        // 최소 주문 금액 30,000원 이상일 때만 할인
        int minimumAmount = 30_000;
        if (orderAmount < minimumAmount) {
            return 0;  // 할인 없음
        }
        return discountValue;  // 할인 적용
    }
}
```

**결과**: ✅ 복잡한 비즈니스 로직도 전략으로 분리하여 관리

---

## 📋 비교표: 기존 설계 vs 개선 설계

| 항목 | 기존 설계 (if-else) | 개선 설계 (전략 패턴) |
|------|-------------------|-------------------|
| **OCP 준수** | ❌ 위반 | ✅ 준수 |
| **확장성** | ❌ 낮음 (기존 코드 수정 필요) | ✅ 높음 (기존 코드 수정 불필요) |
| **분기 처리** | ❌ if-else 증가 | ✅ 전략 추가만 |
| **테스트 용이성** | ❌ 어려움 | ✅ 쉬움 (각 전략 독립 테스트) |
| **단일 책임** | ❌ Coupon이 모든 로직 포함 | ✅ 각 전략이 독립적 |
| **유지보수성** | ❌ 낮음 | ✅ 높음 |

---

## 🎯 설계 원칙 준수

### 1. OCP (Open-Closed Principle)

**✅ 확장에는 열려 있음**:
- 새로운 쿠폰 타입 추가 시 전략 클래스만 추가
- 기존 코드 수정 불필요

**✅ 변경에는 닫혀 있음**:
- `Coupon` 엔티티는 변경 없음
- 기존 전략 클래스는 변경 없음

### 2. SRP (Single Responsibility Principle)

**✅ 각 클래스의 단일 책임**:
- `Coupon`: 쿠폰 정보 관리
- `FixedAmountDiscountStrategy`: 정액 할인 계산
- `PercentageDiscountStrategy`: 정률 할인 계산
- `CouponDiscountStrategyFactory`: 전략 선택

### 3. DIP (Dependency Inversion Principle)

**✅ 추상화에 의존**:
- `Coupon`은 `CouponDiscountStrategy` 인터페이스에 의존
- 구체적인 전략 구현체에 의존하지 않음

---

## 💡 추가 개선 가능 사항

### 1. 전략 등록 자동화 (선택적)

**현재**: Factory 생성자에서 수동 등록
```java
public CouponDiscountStrategyFactory(
    FixedAmountDiscountStrategy fixedAmountStrategy,
    PercentageDiscountStrategy percentageStrategy
) {
    this.strategyMap = Map.of(...);
}
```

**개선 가능**: Spring의 `@Component` 스캔으로 자동 등록
```java
@Component
public class CouponDiscountStrategyFactory {
    private final Map<CouponType, CouponDiscountStrategy> strategyMap;

    public CouponDiscountStrategyFactory(
        List<CouponDiscountStrategy> strategies  // ✅ 자동 주입
    ) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(
                strategy -> strategy.getSupportedType(),  // 전략이 지원하는 타입 반환
                strategy -> strategy
            ));
    }
}
```

**장점**: 새로운 전략 추가 시 Factory 수정 불필요

**단점**: 복잡도 증가, 현재 프로젝트 규모에서는 과한 설계

**결론**: 현재는 수동 등록 방식이 적절 (명확하고 단순)

---

### 2. Specification 패턴 적용 (선택적)

**복잡한 조건이 있는 경우**:
```java
public interface CouponSpecification {
    boolean isSatisfiedBy(Order order, Coupon coupon);
}

@Component
public class MinimumOrderAmountSpecification implements CouponSpecification {
    @Override
    public boolean isSatisfiedBy(Order order, Coupon coupon) {
        return order.getTotalAmount() >= coupon.getMinimumOrderAmount();
    }
}
```

**현재 프로젝트**: 단순한 할인 계산만 필요하므로 전략 패턴으로 충분

**향후 필요 시**: Specification 패턴 추가 고려

---

## 📊 종합 평가

### 현재 설계

| 항목 | 평가 | 설명 |
|------|------|------|
| **OCP 준수** | ✅ 준수 | 전략 패턴으로 확장 가능 |
| **확장성** | ✅ 우수 | 새로운 쿠폰 타입 추가 용이 |
| **테스트 용이성** | ✅ 우수 | 각 전략 독립 테스트 가능 |
| **유지보수성** | ✅ 우수 | 로직 분리로 이해하기 쉬움 |
| **복잡도** | ✅ 적절 | 현재 규모에 적합한 수준 |

### 권장 사항

**✅ 현재 설계 유지**

**이유**:
1. **OCP 준수**: 전략 패턴으로 확장 가능
2. **적절한 복잡도**: 과도한 추상화 없이 필요한 만큼만
3. **명확한 구조**: Factory 패턴으로 전략 선택 로직 명확
4. **테스트 용이**: 각 전략을 독립적으로 테스트 가능

**향후 개선 시 고려**:
- 쿠폰 타입이 10개 이상으로 늘어나면 자동 등록 방식 고려
- 복잡한 조건 검증이 필요하면 Specification 패턴 추가

---

## 🔗 관련 문서

- [동시성 처리 설계 원칙](./15-concurrency-design-principles.md)
- [설계 결정 트레이드오프](./16-design-decision-tradeoffs.md)

