# 쿠폰 설계: 상속 vs 전략 패턴 비교

## 📌 개요

쿠폰 도메인 설계에서 두 가지 접근 방식을 비교합니다:
1. **상속 방식**: `FixedDiscountCoupon`, `PercentageDiscountCoupon`이 `Coupon`을 상속
2. **전략 패턴 방식**: `Coupon` 엔티티 + `CouponDiscountStrategy` 인터페이스 (현재 구현)

---

## 🔄 방식 1: 상속 (Inheritance)

### 구조

```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "coupon_type", discriminatorType = DiscriminatorType.STRING)
@Table(name = "coupon")
public abstract class Coupon extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "code", unique = true, nullable = false)
    private String code;
    
    // 공통 필드
    @Column(name = "discount_value", nullable = false)
    private Integer discountValue;
    
    // 추상 메서드
    public abstract Integer calculateDiscountAmount(Integer orderAmount);
}

@Entity
@DiscriminatorValue("FIXED_AMOUNT")
public class FixedDiscountCoupon extends Coupon {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount) {
        return Math.min(this.getDiscountValue(), orderAmount);
    }
}

@Entity
@DiscriminatorValue("PERCENTAGE")
public class PercentageDiscountCoupon extends Coupon {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount) {
        return (int) Math.round(orderAmount * this.getDiscountValue() / 100.0);
    }
}
```

### 데이터베이스 구조

**SINGLE_TABLE 전략**:
```sql
CREATE TABLE coupon (
    id BIGINT PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    discount_value INT NOT NULL,
    coupon_type VARCHAR(20),  -- discriminator
    -- 모든 서브클래스의 필드가 nullable로 추가됨
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

**JOINED 전략** (대안):
```sql
-- 부모 테이블
CREATE TABLE coupon (
    id BIGINT PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 자식 테이블
CREATE TABLE fixed_discount_coupon (
    coupon_id BIGINT PRIMARY KEY,
    discount_value INT NOT NULL,
    FOREIGN KEY (coupon_id) REFERENCES coupon(id)
);

CREATE TABLE percentage_discount_coupon (
    coupon_id BIGINT PRIMARY KEY,
    discount_value INT NOT NULL,
    FOREIGN KEY (coupon_id) REFERENCES coupon(id)
);
```

---

## 🎯 방식 2: 전략 패턴 (Strategy Pattern) - 현재 구현

### 구조

```java
@Entity
@Table(name = "coupon")
public class Coupon extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "code", unique = true, nullable = false)
    private String code;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private CouponType type;
    
    @Column(name = "discount_value", nullable = false)
    private Integer discountValue;
    
    public Integer calculateDiscountAmount(Integer orderAmount, CouponDiscountStrategyFactory factory) {
        CouponDiscountStrategy strategy = factory.getStrategy(this.type);
        return strategy.calculateDiscountAmount(orderAmount, this.discountValue);
    }
}

public interface CouponDiscountStrategy {
    Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue);
}

@Component
public class FixedAmountDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        return Math.min(discountValue, orderAmount);
    }
}

@Component
public class PercentageDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        return (int) Math.round(orderAmount * discountValue / 100.0);
    }
}
```

### 데이터베이스 구조

```sql
CREATE TABLE coupon (
    id BIGINT PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    type VARCHAR(20) NOT NULL,  -- enum 값
    discount_value INT NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

---

## 📊 비교표

| 항목 | 상속 방식 | 전략 패턴 방식 (현재) |
|------|----------|---------------------|
| **OCP 준수** | ⚠️ 제한적 | ✅ 완전 준수 |
| **확장성** | ⚠️ 중간 | ✅ 우수 |
| **데이터베이스 구조** | ❌ 복잡 (SINGLE_TABLE/JOINED) | ✅ 단순 (단일 테이블) |
| **타입 안정성** | ✅ 우수 (컴파일 타임 체크) | ⚠️ 런타임 체크 |
| **JPA 복잡도** | ❌ 높음 (상속 전략 필요) | ✅ 낮음 (일반 엔티티) |
| **쿼리 성능** | ⚠️ JOIN 필요 (JOINED 전략) | ✅ 단순 조회 |
| **테스트 용이성** | ⚠️ 중간 | ✅ 우수 |
| **유지보수성** | ⚠️ 중간 | ✅ 우수 |
| **각 타입별 특수 필드** | ✅ 가능 | ❌ 어려움 |
| **런타임 타입 변경** | ❌ 불가능 | ✅ 가능 (type 필드 변경) |

---

## 🔍 상세 분석

### 1. OCP (Open-Closed Principle) 준수

#### 상속 방식
```java
// ❌ 새로운 쿠폰 타입 추가 시
@Entity
@DiscriminatorValue("BUY_ONE_GET_ONE")
public class BuyOneGetOneCoupon extends Coupon {
    // Coupon 추상 클래스는 수정 불필요
    // 하지만 Repository, Factory 등 다른 곳 수정 필요할 수 있음
}
```

**문제점**:
- 새로운 쿠폰 타입 추가 시 여러 곳 수정 필요할 수 있음
- Repository에서 타입별 조회 로직 필요
- Factory나 Service에서 타입 체크 로직 필요

#### 전략 패턴 방식
```java
// ✅ 새로운 쿠폰 타입 추가 시
@Component
public class BuyOneGetOneDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        return orderAmount / 2; // 1+1 쿠폰
    }
}

// Factory에만 추가 (Coupon 엔티티 수정 불필요)
this.strategyMap = Map.of(
    CouponType.FIXED_AMOUNT, fixedAmountStrategy,
    CouponType.PERCENTAGE, percentageStrategy,
    CouponType.BUY_ONE_GET_ONE, buyOneGetOneStrategy  // ✅ 추가
);
```

**장점**:
- `Coupon` 엔티티 수정 불필요
- 기존 전략 클래스 수정 불필요
- Factory에만 전략 등록

**결론**: ✅ **전략 패턴이 OCP를 더 잘 준수**

---

### 2. 데이터베이스 구조

#### 상속 방식 (SINGLE_TABLE)
```sql
CREATE TABLE coupon (
    id BIGINT PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    discount_value INT NOT NULL,
    coupon_type VARCHAR(20),  -- discriminator
    -- 문제: 모든 서브클래스의 필드가 nullable로 추가됨
    -- 예: fixed_discount_coupon의 특수 필드, percentage_discount_coupon의 특수 필드 등
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

**문제점**:
- ❌ **NULL 컬럼 증가**: 각 서브클래스의 특수 필드가 모두 nullable로 추가
- ❌ **스키마 복잡도 증가**: 쿠폰 타입이 늘어날수록 컬럼 증가
- ❌ **데이터 무결성 약화**: 특정 타입에만 필요한 필드가 다른 타입에서도 존재

#### 상속 방식 (JOINED)
```sql
-- 부모 테이블
CREATE TABLE coupon (
    id BIGINT PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 자식 테이블들
CREATE TABLE fixed_discount_coupon (
    coupon_id BIGINT PRIMARY KEY,
    discount_value INT NOT NULL,
    FOREIGN KEY (coupon_id) REFERENCES coupon(id)
);

CREATE TABLE percentage_discount_coupon (
    coupon_id BIGINT PRIMARY KEY,
    discount_value INT NOT NULL,
    FOREIGN KEY (coupon_id) REFERENCES coupon(id)
);
```

**문제점**:
- ❌ **JOIN 필요**: 조회 시 항상 JOIN 필요
- ❌ **성능 저하**: JOIN으로 인한 쿼리 성능 저하
- ❌ **스키마 복잡도**: 테이블이 여러 개로 분산

#### 전략 패턴 방식
```sql
CREATE TABLE coupon (
    id BIGINT PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    type VARCHAR(20) NOT NULL,  -- enum 값
    discount_value INT NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

**장점**:
- ✅ **단순한 구조**: 단일 테이블
- ✅ **성능 우수**: JOIN 불필요
- ✅ **스키마 단순**: 컬럼 수 최소화

**결론**: ✅ **전략 패턴이 데이터베이스 구조가 더 단순**

---

### 3. 타입 안정성

#### 상속 방식
```java
// ✅ 컴파일 타임에 타입 체크
FixedDiscountCoupon coupon = new FixedDiscountCoupon(...);
Integer discount = coupon.calculateDiscountAmount(10000);
// → 타입이 명확하므로 컴파일 타임에 체크 가능

// ❌ 하지만 Repository에서 타입 변환 필요
Coupon coupon = couponRepository.findById(id);
if (coupon instanceof FixedDiscountCoupon) {
    FixedDiscountCoupon fixed = (FixedDiscountCoupon) coupon;
    // 타입 캐스팅 필요
}
```

#### 전략 패턴 방식
```java
// ⚠️ 런타임에 타입 체크
Coupon coupon = couponRepository.findById(id);
CouponType type = coupon.getType();  // enum 값
// → 런타임에 타입 확인 필요

// 하지만 Factory에서 타입 체크
CouponDiscountStrategy strategy = factory.getStrategy(type);
// → 타입이 잘못되면 IllegalArgumentException 발생 (런타임)
```

**결론**: ⚠️ **상속 방식이 타입 안정성에서 약간 우수하지만, 실용적 차이는 크지 않음**

---

### 4. 각 타입별 특수 필드 지원

#### 상속 방식
```java
@Entity
@DiscriminatorValue("FIXED_AMOUNT")
public class FixedDiscountCoupon extends Coupon {
    @Column(name = "max_discount_amount")
    private Integer maxDiscountAmount;  // ✅ 정액 쿠폰만의 특수 필드
    
    @Column(name = "minimum_order_amount")
    private Integer minimumOrderAmount;  // ✅ 최소 주문 금액
}

@Entity
@DiscriminatorValue("PERCENTAGE")
public class PercentageDiscountCoupon extends Coupon {
    @Column(name = "max_discount_percentage")
    private Integer maxDiscountPercentage;  // ✅ 정률 쿠폰만의 특수 필드
}
```

**장점**: 각 쿠폰 타입별로 독립적인 필드 추가 가능

#### 전략 패턴 방식
```java
@Entity
@Table(name = "coupon")
public class Coupon extends BaseEntity {
    // ❌ 모든 쿠폰 타입이 공유하는 필드만 가능
    @Column(name = "discount_value")
    private Integer discountValue;
    
    // 특수 필드를 추가하려면?
    // → 모든 쿠폰 타입에 nullable 필드로 추가해야 함
    @Column(name = "max_discount_amount")
    private Integer maxDiscountAmount;  // ❌ 정액 쿠폰만 사용하는데 nullable로 추가
}
```

**문제점**: 특수 필드 추가 시 모든 타입에 nullable로 추가해야 함

**결론**: ✅ **상속 방식이 각 타입별 특수 필드 지원에 유리**

---

### 5. JPA 복잡도

#### 상속 방식
```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "coupon_type")
@DiscriminatorValue("BASE")
public abstract class Coupon extends BaseEntity {
    // ...
}

// Repository에서 타입별 조회
public interface CouponRepository extends JpaRepository<Coupon, Long> {
    // ❌ 타입별 조회가 복잡함
    @Query("SELECT c FROM FixedDiscountCoupon c")
    List<FixedDiscountCoupon> findAllFixedCoupons();
    
    // 또는
    @Query("SELECT c FROM Coupon c WHERE TYPE(c) = FixedDiscountCoupon")
    List<Coupon> findAllFixedCoupons();
}
```

**문제점**:
- JPA 상속 전략 이해 필요
- Discriminator 관리 필요
- 타입별 조회 쿼리 복잡

#### 전략 패턴 방식
```java
@Entity
@Table(name = "coupon")
public class Coupon extends BaseEntity {
    // 일반 엔티티와 동일
}

// Repository에서 타입별 조회
public interface CouponRepository extends JpaRepository<Coupon, Long> {
    // ✅ 단순한 enum 기반 조회
    List<Coupon> findByType(CouponType type);
}
```

**장점**:
- 일반 엔티티와 동일한 방식
- 복잡한 JPA 상속 전략 불필요

**결론**: ✅ **전략 패턴이 JPA 복잡도가 낮음**

---

### 6. 확장 시나리오 비교

#### 시나리오: 1+1 쿠폰 추가

**상속 방식**:
```java
// 1. 새로운 엔티티 생성
@Entity
@DiscriminatorValue("BUY_ONE_GET_ONE")
public class BuyOneGetOneCoupon extends Coupon {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount) {
        return orderAmount / 2;
    }
}

// 2. Repository 수정 (타입별 조회 추가)
@Query("SELECT c FROM BuyOneGetOneCoupon c")
List<BuyOneGetOneCoupon> findAllBuyOneGetOneCoupons();

// 3. Service/Factory에서 타입 체크 로직 추가
if (coupon instanceof BuyOneGetOneCoupon) {
    // 처리
}
```

**전략 패턴 방식**:
```java
// 1. 전략 구현체 생성
@Component
public class BuyOneGetOneDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public Integer calculateDiscountAmount(Integer orderAmount, Integer discountValue) {
        return orderAmount / 2;
    }
}

// 2. Factory에 등록 (Coupon 엔티티, Repository 수정 불필요)
this.strategyMap = Map.of(
    CouponType.FIXED_AMOUNT, fixedAmountStrategy,
    CouponType.PERCENTAGE, percentageStrategy,
    CouponType.BUY_ONE_GET_ONE, buyOneGetOneStrategy  // ✅ 추가
);
```

**결론**: ✅ **전략 패턴이 확장이 더 용이**

---

## 🎯 현재 프로젝트에 적합한 방식

### 현재 프로젝트 특성

1. **쿠폰 타입이 단순**: 정액/정률만 존재, 특수 필드 없음
2. **데이터베이스 구조 단순화 선호**: 단일 테이블 구조
3. **확장성 중시**: 새로운 쿠폰 타입 추가 용이
4. **JPA 복잡도 최소화**: 일반 엔티티 구조 선호

### 권장: 전략 패턴 방식 (현재 구현)

**이유**:
1. ✅ **OCP 준수**: 확장에 열려 있고 변경에 닫혀 있음
2. ✅ **데이터베이스 단순**: 단일 테이블 구조로 성능 우수
3. ✅ **JPA 복잡도 낮음**: 일반 엔티티와 동일한 방식
4. ✅ **확장 용이**: 새로운 쿠폰 타입 추가 시 최소 변경
5. ✅ **테스트 용이**: 각 전략을 독립적으로 테스트 가능

---

## ⚠️ 상속 방식을 선택해야 하는 경우

### 시나리오: 각 쿠폰 타입별로 특수 필드가 많은 경우

```java
// 정액 쿠폰: 최대 할인 금액, 최소 주문 금액, 적용 가능 카테고리
@Entity
@DiscriminatorValue("FIXED_AMOUNT")
public class FixedDiscountCoupon extends Coupon {
    private Integer maxDiscountAmount;
    private Integer minimumOrderAmount;
    private List<String> applicableCategories;
}

// 정률 쿠폰: 최대 할인 비율, 최소 주문 금액, 제외 상품 목록
@Entity
@DiscriminatorValue("PERCENTAGE")
public class PercentageDiscountCoupon extends Coupon {
    private Integer maxDiscountPercentage;
    private Integer minimumOrderAmount;
    private List<Long> excludedProductIds;
}

// 무료배송 쿠폰: 배송비 금액, 적용 가능 지역
@Entity
@DiscriminatorValue("FREE_SHIPPING")
public class FreeShippingCoupon extends Coupon {
    private Integer shippingFee;
    private List<String> applicableRegions;
}
```

**이 경우 상속 방식이 더 적합**:
- ✅ 각 타입별로 독립적인 필드 관리
- ✅ 타입 안정성 보장
- ✅ 데이터 무결성 향상 (필수 필드가 nullable이 아님)

---

## 📋 최종 비교표

| 평가 기준 | 상속 방식 | 전략 패턴 방식 | 승자 |
|----------|----------|--------------|------|
| **OCP 준수** | ⚠️ 제한적 | ✅ 우수 | 전략 패턴 |
| **데이터베이스 구조** | ❌ 복잡 | ✅ 단순 | 전략 패턴 |
| **JPA 복잡도** | ❌ 높음 | ✅ 낮음 | 전략 패턴 |
| **확장성** | ⚠️ 중간 | ✅ 우수 | 전략 패턴 |
| **타입 안정성** | ✅ 우수 | ⚠️ 중간 | 상속 |
| **특수 필드 지원** | ✅ 우수 | ❌ 어려움 | 상속 |
| **쿼리 성능** | ⚠️ JOIN 필요 | ✅ 단순 조회 | 전략 패턴 |
| **테스트 용이성** | ⚠️ 중간 | ✅ 우수 | 전략 패턴 |

---

## 💡 결론

### 현재 프로젝트: 전략 패턴 방식 권장 ✅

**이유**:
1. 쿠폰 타입이 단순하고 특수 필드가 없음
2. 데이터베이스 구조 단순화 선호
3. 확장성과 유지보수성 중시
4. OCP 준수 중요

### 상속 방식을 고려해야 하는 경우

다음 조건이 모두 충족될 때:
- ✅ 각 쿠폰 타입별로 **많은 특수 필드**가 필요
- ✅ 타입별 필드가 **서로 완전히 다름** (공통 필드 적음)
- ✅ 타입 안정성이 **매우 중요**
- ✅ 데이터 무결성 강화 필요 (필수 필드가 nullable이 아니어야 함)

---

## 🔗 관련 문서

- [쿠폰 전략 패턴 리팩토링](./22-coupon-strategy-pattern-refactoring.md)
- [동시성 처리 설계 원칙](./15-concurrency-design-principles.md)

