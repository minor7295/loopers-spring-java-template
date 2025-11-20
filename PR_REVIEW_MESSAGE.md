## 📌 Summary

Round 3 요구사항에 따라 **상품, 브랜드, 좋아요, 주문 기능의 도메인 모델 및 도메인 서비스**를 구현했습니다. 

주요 작업 내용:
- **Aggregate 경계 설계**: User, Product, Order, Brand, Like를 독립적인 Aggregate로 구성
- **Point 도메인 통합**: Point를 User Aggregate의 Embedded Value Object로 설계
- **간접 참조 설계**: Aggregate 간 참조는 ID를 통해서만 이루어지도록 구성
- **도메인 서비스 설계**: 도메인 간 협력 로직을 Domain Service로 분리 (ProductDetailService)
- **단위 테스트 구성**: Mock/Stub을 활용한 테스트 가능한 구조 설계

---

## 💬 Review Points

### 1. Aggregate 경계 설계 및 간접 참조 구성

각 도메인을 독립적인 Aggregate로 구성하고, Aggregate 간 참조는 ID를 통한 간접 참조로 설계했습니다. 이렇게 설계한 이유는 각 Aggregate의 독립성을 보장하고, 다른 Aggregate의 변경에 영향을 받지 않도록 하기 위함입니다. 또한 트랜잭션 경계를 명확히 하여 각 Aggregate가 자신의 일관성 경계 내에서만 트랜잭션을 관리하도록 했으며, Aggregate 간 결합도를 낮춰 향후 변경에 유연하게 대응할 수 있도록 했습니다.

**Order Aggregate의 간접 참조 예시:**
```java
// Order는 User와 Product를 직접 참조하지 않고 ID만 저장
@Column(name = "ref_user_id", nullable = false)
private Long userId;  // User Aggregate를 ID로만 참조

// OrderItem도 Product를 ID로만 참조하며, 주문 시점의 스냅샷 정보를 저장
private Long productId;  // Product Aggregate를 ID로만 참조
private String name;     // 주문 시점의 상품명 스냅샷
private Integer price;   // 주문 시점의 가격 스냅샷
```

**Product와 Like Aggregate의 간접 참조:**
```java
// Product는 Brand를 ID로만 참조
@Column(name = "ref_brand_id", nullable = false)
private Long brandId;

// Like는 User와 Product를 ID로만 참조
@Column(name = "ref_user_id", nullable = false)
private Long userId;
@Column(name = "ref_product_id", nullable = false)
private Long productId;
```

---

### 2. Point 도메인을 User Aggregate로 통합

Point를 별도 Aggregate가 아닌 User Aggregate의 Embedded Value Object로 설계했습니다. Point는 User와 생명주기가 일치하여 함께 생성/삭제되므로 User의 일부로 관리하는 것이 적절합니다. 또한 Point를 Value Object로 설계하여 불변성을 보장하고 부작용을 방지하며, 포인트 차감/충전 로직을 Point 내부에 캡슐화하여 도메인 규칙을 명확히 했습니다.

**Point를 Embedded Value Object로 설계:**
```java
// User.java
@Embedded
private Point point;  // Point는 User의 일부로 관리

// Point.java
@Embeddable  // JPA Embedded Value Object
public class Point {
    @Column(name = "balance", nullable = false)
    private final Long value;  // 불변 값 객체
    
    public Point subtract(Point other) {
        // 포인트 차감 로직이 Point 내부에 캡슐화
        if (this.value < other.value) {
            throw new CoreException(ErrorType.BAD_REQUEST, "포인트가 부족합니다.");
        }
        return new Point(this.value - other.value);
    }
}

// User가 자신의 포인트를 직접 관리
public void deductPoint(Point point) {
    this.point = this.point.subtract(point);  // Point의 불변성 보장
}
```

---

### 3. Entity vs Value Object 설계 결정

각 도메인 요소를 Entity 또는 Value Object로 구분하여 설계했습니다. Entity는 고유한 ID를 가지며 생명주기를 가지는 변경 가능한 객체로 설계했고(예: `User`, `Product`, `Order`), Value Object는 값으로 식별되며 불변성을 가진 객체로 설계했습니다(예: `Point`, `OrderItem`). 특히 `OrderItem`을 Value Object로 설계한 이유는 주문 시점의 상품 정보를 스냅샷으로 저장하여, 이후 상품 정보가 변경되어도 주문 내역은 변경되지 않도록 보장하기 위함입니다.

**OrderItem을 Value Object로 설계:**
```java
// OrderItem.java - Value Object
@Getter
@EqualsAndHashCode
public class OrderItem {
    private Long productId;    // 주문 시점의 스냅샷
    private String name;       // 불변 값
    private Integer price;     // 불변 값
    private Integer quantity;  // 불변 값
}
```

---

### 4. 도메인 서비스를 통한 Aggregate 간 협력

도메인 간 협력 로직을 Domain Service로 분리하여 상태 없이 도메인 객체의 협력 중심으로 설계했습니다. `ProductDetailService`는 Repository 의존성 없이 도메인 객체만 파라미터로 받아 처리하므로, Aggregate 간 협력 로직을 명확히 분리할 수 있고, Repository 의존성 없이 순수 도메인 객체만으로 테스트가 가능하며, 여러 Application Service에서 동일한 도메인 서비스를 재사용할 수 있습니다.

**ProductDetailService - 도메인 객체 협력 중심 설계:**
```java
// ProductDetailService.java
@Component
public class ProductDetailService {
    // Repository 의존성 없이 도메인 객체만 파라미터로 받음
    public ProductDetail combineProductAndBrand(Product product, Brand brand, Long likesCount) {
        // 상태 없이 순수한 조합 로직만 처리
        return ProductDetail.of(
            product.getId(),
            product.getName(),
            product.getPrice(),
            product.getStock(),
            product.getBrandId(),
            brand.getName(),
            likesCount
        );
    }
}
```

**Application Layer에서 도메인 서비스 활용:**
```java
// CatalogProductFacade.java
public ProductInfo getProduct(Long productId) {
    Product product = productRepository.findById(productId)...
    Brand brand = brandRepository.findById(product.getBrandId())...
    Long likesCount = likeRepository.countByProductIds(...)...
    
    // 도메인 서비스를 통해 도메인 객체 협력
    ProductDetail productDetail = productDetailService.combineProductAndBrand(
        product, brand, likesCount
    );
    
    return new ProductInfo(productDetail);
}
```

---

### 5. Repository Interface와 구현체 분리 및 단위 테스트를 위한 Mock/Stub 활용

Repository Interface는 Domain Layer에, 구현체는 Infrastructure Layer에 위치시켜 의존성 역전 원칙(DIP)을 준수했습니다. 이 설계로 Domain Layer가 Infrastructure Layer에 의존하지 않게 되었고, Repository Interface를 통해 Mock Repository로 쉽게 대체할 수 있어 단위 테스트가 가능해졌습니다. 외부 의존성(DB, 네트워크) 없이 빠르게 테스트를 실행할 수 있고, 각 테스트가 독립적으로 실행되어 안정성을 확보할 수 있으며, Mock/Stub을 통해 테스트하고자 하는 의도를 명확히 표현할 수 있습니다.

---

### 6. 설계 문서 업데이트 및 구현 내용 반영

구현된 코드와 설계 문서의 일관성을 확보하고, 비개발자도 이해할 수 있도록 문서를 개선했습니다. 주요 변경 사항은 다음과 같습니다:

**구현 내용 반영:**
- `ProductDisplay`/`ProductOrder` 개념을 실제 구현인 `Product`로 통합 반영
- `LikeService`를 실제 구현인 `LikeFacade`로 수정
- `OrderService`를 실제 구현인 `PurchasingFacade`로 수정
- 주문 생성 흐름을 실제 구현에 맞게 수정 (재고 차감 → 포인트 차감 → 주문 완료 순서)

**문서 구조 개선:**
- **03-class-diagram.md**: Aggregate 중심으로 재구성
  - Aggregate 구분 다이어그램을 맨 위에 배치하여 전체 구조를 먼저 제시
  - 각 Aggregate별로 독립적인 섹션 구성 (User, Product, Order, Brand, Like)
  - 각 Aggregate의 클래스 다이어그램, 설명, 주요 특징을 명확히 구분
  - Order 상태 전이 다이어그램 추가
  - Aggregate 간 협력 예시 추가 (PurchasingFacade, LikeFacade)

- **02-sequence-diagrams.md**: 비개발자용으로 재작성
  - 기술 용어를 비즈니스 용어로 변경 (Controller → 웹사이트, Facade → 시스템)
  - 각 시나리오에 상세한 설명 추가
  - 주문 실패 시나리오 추가

- **01-requirements.md**: 비개발자용 용어로 변경
  - 기술 용어 제거 (HTTP 상태 코드, 트랜잭션, 멱등성 등)
  - 사용자 친화적인 메시지로 변경
  - 비기능 요구사항을 비개발자가 이해할 수 있는 표현으로 수정

- **04-erd.md**: 데이터베이스 설계 참고사항 추가
  - Point 테이블 관련 설명 추가
  - Aggregate 경계 설명 추가

**문서 위치:**
- `.docs/design/01-requirements.md` - 요구사항 및 유스케이스 명세서
- `.docs/design/02-sequence-diagrams.md` - 시퀀스 다이어그램 명세서
- `.docs/design/03-class-diagram.md` - 클래스 다이어그램 명세서
- `.docs/design/04-erd.md` - ERD 명세서

**Repository Interface와 구현체 분리:**
```java
// Domain Layer: domain/product/ProductRepository.java
public interface ProductRepository {
    Product save(Product product);
    Optional<Product> findById(Long productId);
    List<Product> findAll(Long brandId, String sort, int page, int size);
}

// Infrastructure Layer: infrastructure/product/ProductRepositoryImpl.java
@Repository
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;
    
    @Override
    public Product save(Product product) {
        return productJpaRepository.save(product);
    }
}
```

**DIP를 통한 Mock/Stub 테스트:**
```java
// LikeFacadeTest - Repository Interface를 Mock으로 대체
@BeforeEach
void setUp() {
    userRepository = mock(UserRepository.class);
    productRepository = mock(ProductRepository.class);
    likeRepository = mock(LikeRepository.class);
    
    likeFacade = new LikeFacade(likeRepository, userRepository, productRepository);
}

@Test
@DisplayName("좋아요는 중복 등록되지 않는다.")
void addLike_isIdempotent() {
    when(likeRepository.findByUserIdAndProductId(...))
        .thenReturn(Optional.of(Like.of(...)));  // Stub 설정
    
    likeFacade.addLike(DEFAULT_USER_ID, DEFAULT_PRODUCT_ID);
    
    verify(likeRepository, never()).save(any(Like.class));
}
```

---

## ✅ Checklist

### 🏷 Product / Brand 도메인
- [x] 상품 정보 객체는 브랜드 정보, 좋아요 수를 포함한다 (`ProductDetail`에 `brandId`, `brandName`, `likesCount` 포함)
- [x] 상품의 정렬 조건(`latest`, `price_asc`, `likes_desc`)을 고려한 조회 기능을 설계했다 (`ProductRepository.findAll()`)
- [x] 상품은 재고를 가지고 있고, 주문 시 차감할 수 있어야 한다 (`Product.decreaseStock()`)
- [x] 재고는 감소만 가능하며 음수 방지는 도메인 레벨에서 처리된다 (`Product.decreaseStock()`에서 검증)

### 👍 Like 도메인
- [x] 좋아요는 유저와 상품 간의 관계로 별도 도메인으로 분리했다 (`Like` 엔티티)
- [x] 중복 좋아요 방지를 위한 멱등성 처리가 구현되었다 (`LikeFacade.addLike()`에서 중복 체크)
- [x] 상품의 좋아요 수는 상품 상세/목록 조회에서 함께 제공된다 (`CatalogProductFacade.getProduct()`, `getProducts()`)
- [x] 단위 테스트에서 좋아요 등록/취소/중복 방지 흐름을 검증했다 (`LikeFacadeTest`)

### 🛒 Order 도메인
- [x] 주문은 여러 상품을 포함할 수 있으며, 각 상품의 수량을 명시한다 (`OrderItem`에 `quantity` 포함)
- [x] 주문 시 상품의 재고 차감, 유저 포인트 차감 등을 수행한다 (`PurchasingFacade.createOrder()`)
- [x] 재고 부족, 포인트 부족 등 예외 흐름을 고려해 설계되었다 (`Product.decreaseStock()`, `Point.subtract()`)
- [x] 단위 테스트에서 정상 주문 / 예외 주문 흐름을 모두 검증했다 (`PurchasingFacadeTest`, `OrderTest`)

### 🧩 도메인 서비스
- [x] 도메인 간 협력 로직은 Domain Service에 위치시켰다 (`ProductDetailService`)
- [x] 상품 상세 조회 시 Product + Brand 정보 조합은 도메인 서비스에서 처리했다 (`ProductDetailService.combineProductAndBrand()`)
- [x] 복합 유스케이스는 Application Layer에 존재하고, 도메인 로직은 위임되었다 (`CatalogProductFacade` → `ProductDetailService`)
- [x] 도메인 서비스는 상태 없이, 도메인 객체의 협력 중심으로 설계되었다 (`ProductDetailService`는 Repository 의존성 없음)

### 🧱 소프트웨어 아키텍처 & 설계
- [x] 전체 프로젝트의 구성은 Application → Domain ← Infrastructure 아키텍처를 기반으로 구성되었다
- [x] Application Layer는 도메인 객체를 조합해 흐름을 orchestration 했다 (`CatalogProductFacade`, `PurchasingFacade`)
- [x] 핵심 비즈니스 로직은 Entity, VO, Domain Service에 위치한다
- [x] Repository Interface는 Domain Layer에 정의되고, 구현체는 Infra에 위치한다
- [x] 패키지는 계층 + 도메인 기준으로 구성되었다 (`/domain/order`, `/application/like` 등)

### 테스트
- [x] 테스트는 외부 의존성을 분리하고, Fake/Stub 등을 사용해 단위 테스트가 가능하게 구성되었다 (`LikeFacadeTest`에서 Mock 사용)

---

## 📎 References

### 설계 문서
- `.docs/design/06-aggregate-analysis.md` - Aggregate 경계 및 관계 분석
- `.docs/design/05-domain-service-vs-application-service.md` - 도메인 서비스 vs 애플리케이션 서비스 설계 가이드

### 주요 코드 위치
- **Aggregate Root**: `apps/commerce-api/src/main/java/com/loopers/domain/*/`
- **Value Object**: `apps/commerce-api/src/main/java/com/loopers/domain/user/Point.java`, `apps/commerce-api/src/main/java/com/loopers/domain/order/OrderItem.java`
- **Domain Service**: `apps/commerce-api/src/main/java/com/loopers/domain/product/ProductDetailService.java`
- **Application Service**: `apps/commerce-api/src/main/java/com/loopers/application/*/`
- **Repository Interface**: `apps/commerce-api/src/main/java/com/loopers/domain/*/Repository.java`
- **Repository 구현체**: `apps/commerce-api/src/main/java/com/loopers/infrastructure/*/RepositoryImpl.java`
- **단위 테스트**: `apps/commerce-api/src/test/java/com/loopers/application/like/LikeFacadeTest.java`, `apps/commerce-api/src/test/java/com/loopers/domain/product/ProductDetailServiceTest.java`

