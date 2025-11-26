# 캐시 패턴 적용 가이드

이 문서는 프로젝트에 Cache Aside, Write Through, Write Back 패턴을 어떻게 적용하면 좋을지 분석한 가이드입니다.

## 목차

1. [캐시 패턴 개요](#캐시-패턴-개요)
2. [현재 프로젝트의 캐시 구조](#현재-프로젝트의-캐시-구조)
3. [도메인별 캐시 패턴 적용 방안](#도메인별-캐시-패턴-적용-방안)
4. [구현 예시](#구현-예시)

---

## 캐시 패턴 개요

### 1. Cache Aside (Lazy Loading)

**동작 방식:**
- **읽기**: 캐시 확인 → 없으면 DB 조회 → 캐시 저장
- **쓰기**: DB 업데이트 → 캐시 무효화 (또는 캐시 업데이트)

**장점:**
- 구현이 단순
- 캐시 장애 시에도 DB로 폴백 가능
- 캐시에 없는 데이터는 저장하지 않아 메모리 효율적

**단점:**
- 캐시 미스 시 2번의 네트워크 호출 (캐시 확인 + DB 조회)
- 캐시와 DB 간 일관성 보장이 애플리케이션 레벨에서 필요

**적용 대상:**
- 읽기 비율이 높은 데이터
- 자주 변경되지 않는 데이터
- 캐시 미스가 허용 가능한 데이터

---

### 2. Write Through

**동작 방식:**
- **읽기**: 캐시 확인 → 없으면 DB 조회 → 캐시 저장
- **쓰기**: 캐시 업데이트 → DB 업데이트 (동기적으로)

**장점:**
- 캐시와 DB의 일관성 보장
- 읽기 시 항상 캐시 히트 (쓰기 후 캐시에 최신 데이터 존재)

**단점:**
- 쓰기 성능 저하 (캐시 + DB 모두 업데이트)
- 캐시 장애 시 쓰기 실패

**적용 대상:**
- 자주 업데이트되지만 읽기 비율이 매우 높은 데이터
- 일관성이 중요한 데이터
- 캐시 장애 시 쓰기 실패가 허용 가능한 경우

---

### 3. Write Back (Write Behind)

**동작 방식:**
- **읽기**: 캐시 확인 → 없으면 DB 조회 → 캐시 저장
- **쓰기**: 캐시 업데이트 → 비동기로 DB 업데이트 (배치 처리)

**장점:**
- 쓰기 성능 최적화 (캐시만 업데이트)
- DB 부하 감소 (배치 처리)

**단점:**
- 캐시 장애 시 데이터 손실 위험
- 일관성 보장 어려움
- 복잡한 구현 (배치 처리, 장애 복구)

**적용 대상:**
- 쓰기 비율이 높은 데이터
- 일관성 요구가 낮은 데이터 (예: 조회수, 좋아요 수)
- DB 부하를 줄이고 싶은 경우

---

## 현재 프로젝트의 캐시 구조

### 현재 적용된 패턴

#### 1. 상품 조회 (Cache Aside)

**위치**: `CatalogProductFacade`, `ProductCacheService`

**현재 구현:**
```java
// 읽기
public ProductInfo getProduct(Long productId) {
    // 1. 캐시 확인
    ProductInfo cached = productCacheService.getCachedProduct(productId);
    if (cached != null) {
        return cached;
    }
    
    // 2. DB 조회
    Product product = productRepository.findById(productId);
    // ...
    
    // 3. 캐시 저장
    productCacheService.cacheProduct(productId, result);
    return result;
}

// 쓰기 (좋아요 수 업데이트)
public void updateLikeCount(Long productId, Long likeCount) {
    // 1. DB 업데이트
    product.updateLikeCount(likeCount);
    productRepository.save(product);
    
    // 2. 캐시 무효화
    productCacheService.evictProductCache(productId);
}
```

**특징:**
- ✅ Cache Aside 패턴 적용
- ✅ 읽기 중심 데이터에 적합
- ⚠️ 좋아요 수 업데이트 시 캐시 무효화로 인한 캐시 미스 발생

---

#### 2. 사용자 정보 조회 (Cache Aside - 로컬 캐시)

**위치**: `UserInfoFacade`

**현재 구현:**
```java
@Cacheable(cacheNames = "userInfo", key = "#userId")
public UserInfo getUserInfo(String userId) {
    User user = userRepository.findByUserId(userId);
    // ...
    return UserInfo.from(user);
}
```

**특징:**
- ✅ Cache Aside 패턴 적용 (Caffeine 로컬 캐시)
- ✅ 읽기 중심 데이터에 적합
- ⚠️ 사용자 정보 업데이트 시 캐시 무효화 필요 (현재 미구현)

---

## 도메인별 캐시 패턴 적용 방안

### 1. 상품 정보 (Product)

#### 현재 상태
- **패턴**: Cache Aside
- **읽기**: 상품 상세, 상품 목록 (첫 페이지만)
- **쓰기**: 좋아요 수 업데이트 시 캐시 무효화

#### 개선 방안

##### A. 상품 가격/재고 업데이트 → Write Through 고려

**시나리오**: 관리자가 상품 가격이나 재고를 자주 업데이트하지만, 조회 비율이 매우 높은 경우

**적용 이유:**
- 가격/재고는 일관성이 중요 (주문 시 정확한 가격 필요)
- 업데이트 후 즉시 조회 시 최신 데이터 보장 필요
- Write Through로 캐시와 DB 일관성 보장

**구현 예시:**
```java
public void updateProductPrice(Long productId, Integer newPrice) {
    // 1. DB 업데이트
    Product product = productRepository.findById(productId);
    product.updatePrice(newPrice);
    productRepository.save(product);
    
    // 2. 캐시 업데이트 (Write Through)
    ProductInfo updatedInfo = ProductInfo.from(product, brand.getName(), product.getLikeCount());
    productCacheService.cacheProduct(productId, updatedInfo);
}
```

**주의사항:**
- 캐시 업데이트 실패 시 처리 전략 필요
- 트랜잭션 롤백 시 캐시도 롤백 필요

---

##### B. 좋아요 수 업데이트 → Write Back 고려

**시나리오**: 좋아요 수는 자주 업데이트되지만, 정확한 일관성보다는 성능이 중요한 경우

**적용 이유:**
- 좋아요 수는 집계 데이터 (약간의 지연 허용 가능)
- 쓰기 비율이 높음 (좋아요/좋아요 취소)
- DB 부하 감소 필요

**구현 예시:**
```java
// 비동기 배치 처리로 DB 업데이트
@Async
public void updateLikeCountAsync(Long productId, Long delta) {
    // 1. 캐시만 즉시 업데이트
    ProductInfo cached = productCacheService.getCachedProduct(productId);
    if (cached != null) {
        // 캐시의 좋아요 수만 즉시 업데이트
        ProductInfo updated = updateLikeCountInCache(cached, delta);
        productCacheService.cacheProduct(productId, updated);
    }
    
    // 2. DB는 비동기로 배치 처리
    likeCountBatchService.addToBatch(productId, delta);
}
```

**주의사항:**
- 캐시 장애 시 데이터 손실 가능
- 배치 처리 실패 시 복구 전략 필요
- 최종 일관성 보장 필요

---

### 2. 사용자 정보 (User)

#### 현재 상태
- **패턴**: Cache Aside (로컬 캐시)
- **읽기**: 사용자 정보 조회
- **쓰기**: 캐시 무효화 미구현

#### 개선 방안

##### A. 사용자 정보 업데이트 → Write Through 고려

**시나리오**: 사용자가 프로필을 업데이트하지만, 조회 비율이 높은 경우

**적용 이유:**
- 프로필 업데이트 후 즉시 조회 시 최신 데이터 필요
- 일관성 중요 (인증/인가에 사용)
- 로컬 캐시이므로 Write Through 구현이 상대적으로 단순

**구현 예시:**
```java
@CachePut(cacheNames = "userInfo", key = "#userId")
public UserInfo updateUserInfo(String userId, String email, LocalDate birthDate) {
    // 1. DB 업데이트
    User user = userRepository.findByUserId(userId);
    user.updateProfile(email, birthDate);
    userRepository.save(user);
    
    // 2. 캐시 업데이트 (Write Through)
    // @CachePut으로 자동 처리
    return UserInfo.from(user);
}
```

---

### 3. 주문 정보 (Order)

#### 현재 상태
- **패턴**: 캐시 미적용
- **특징**: 트랜잭션 중심, 동시성 제어 중요

#### 적용 방안

##### A. 주문 조회 → Cache Aside 고려

**시나리오**: 주문 내역 조회가 빈번하지만, 주문 생성 후 즉시 조회는 드문 경우

**적용 이유:**
- 주문 조회는 읽기 중심
- 주문 생성은 트랜잭션 중심 (캐시 적용 불필요)
- 주문 조회 시 캐시 적용으로 DB 부하 감소

**구현 예시:**
```java
@Cacheable(cacheNames = "order", key = "#orderId")
public OrderInfo getOrder(Long orderId) {
    Order order = orderRepository.findById(orderId);
    return OrderInfo.from(order);
}

// 주문 생성 시 캐시 무효화는 불필요 (새로운 주문이므로)
```

**주의사항:**
- 주문 상태 변경 시 캐시 무효화 필요
- 주문 취소/환불 시 캐시 무효화 필요

---

### 4. 브랜드 정보 (Brand)

#### 현재 상태
- **패턴**: 캐시 미적용
- **특징**: 마스터 데이터, 자주 변경되지 않음

#### 적용 방안

##### A. 브랜드 조회 → Cache Aside (로컬 캐시) 고려

**시나리오**: 브랜드 정보는 거의 변경되지 않지만, 상품 조회 시마다 조회됨

**적용 이유:**
- 읽기 중심 데이터
- 변경 빈도가 매우 낮음
- 로컬 캐시로 충분 (인스턴스별 캐시)

**구현 예시:**
```java
@Cacheable(cacheNames = "brand", key = "#brandId")
public BrandInfo getBrand(Long brandId) {
    Brand brand = brandRepository.findById(brandId);
    return BrandInfo.from(brand);
}
```

---

## 구현 예시

### 1. Write Through 패턴 구현 (상품 가격 업데이트)

```java
@Service
@RequiredArgsConstructor
public class ProductWriteThroughService {
    private final ProductRepository productRepository;
    private final ProductCacheService productCacheService;
    private final BrandRepository brandRepository;
    
    @Transactional
    public void updateProductPrice(Long productId, Integer newPrice) {
        // 1. DB 업데이트
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        product.updatePrice(newPrice);
        Product savedProduct = productRepository.save(product);
        
        // 2. 브랜드 조회 (캐시 업데이트용)
        Brand brand = brandRepository.findById(savedProduct.getBrandId())
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        
        // 3. 캐시 업데이트 (Write Through)
        ProductInfo updatedInfo = new ProductInfo(
            ProductDetail.from(savedProduct, brand.getName(), savedProduct.getLikeCount())
        );
        productCacheService.cacheProduct(productId, updatedInfo);
    }
}
```

---

### 2. Write Back 패턴 구현 (좋아요 수 업데이트)

```java
@Service
@RequiredArgsConstructor
public class LikeCountWriteBackService {
    private final ProductCacheService productCacheService;
    private final LikeCountBatchProcessor likeCountBatchProcessor;
    
    public void incrementLikeCount(Long productId) {
        // 1. 캐시만 즉시 업데이트
        ProductInfo cached = productCacheService.getCachedProduct(productId);
        if (cached != null) {
            ProductInfo updated = incrementLikeCountInCache(cached);
            productCacheService.cacheProduct(productId, updated);
        }
        
        // 2. DB는 비동기 배치 처리
        likeCountBatchProcessor.addToBatch(productId, 1L);
    }
    
    private ProductInfo incrementLikeCountInCache(ProductInfo cached) {
        ProductDetail detail = cached.productDetail();
        ProductDetail updatedDetail = ProductDetail.of(
            detail.getId(),
            detail.getName(),
            detail.getPrice(),
            detail.getStock(),
            detail.getBrandId(),
            detail.getBrandName(),
            detail.getLikesCount() + 1  // 좋아요 수 증가
        );
        return new ProductInfo(updatedDetail);
    }
}

@Component
@RequiredArgsConstructor
public class LikeCountBatchProcessor {
    private final ProductRepository productRepository;
    private final BlockingQueue<LikeCountUpdate> updateQueue = new LinkedBlockingQueue<>();
    
    @PostConstruct
    public void startBatchProcessor() {
        // 주기적으로 배치 처리
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::processBatch, 5, 5, TimeUnit.SECONDS);
    }
    
    public void addToBatch(Long productId, Long delta) {
        updateQueue.offer(new LikeCountUpdate(productId, delta));
    }
    
    private void processBatch() {
        Map<Long, Long> aggregatedUpdates = new HashMap<>();
        
        // 큐에서 업데이트 수집
        LikeCountUpdate update;
        while ((update = updateQueue.poll()) != null) {
            aggregatedUpdates.merge(update.productId(), update.delta(), Long::sum);
        }
        
        // 배치로 DB 업데이트
        if (!aggregatedUpdates.isEmpty()) {
            aggregatedUpdates.forEach((productId, totalDelta) -> {
                Product product = productRepository.findById(productId).orElse(null);
                if (product != null) {
                    product.updateLikeCount(product.getLikeCount() + totalDelta);
                    productRepository.save(product);
                }
            });
        }
    }
    
    private record LikeCountUpdate(Long productId, Long delta) {}
}
```

---

## 패턴 선택 가이드

### 패턴 선택 기준

| 기준 | Cache Aside | Write Through | Write Back |
|------|-------------|--------------|------------|
| **읽기 비율** | 높음 | 매우 높음 | 보통 |
| **쓰기 비율** | 낮음 | 보통 | 높음 |
| **일관성 요구** | 보통 | 높음 | 낮음 |
| **구현 복잡도** | 낮음 | 보통 | 높음 |
| **캐시 장애 영향** | 낮음 (DB 폴백) | 높음 (쓰기 실패) | 매우 높음 (데이터 손실) |

### 도메인별 권장 패턴

| 도메인 | 현재 패턴 | 권장 패턴 | 이유 |
|--------|----------|----------|------|
| **상품 조회** | Cache Aside | Cache Aside | 읽기 중심, 적합 |
| **상품 가격/재고** | Cache Aside | Write Through | 일관성 중요, 업데이트 후 즉시 조회 |
| **좋아요 수** | Cache Aside | Write Back | 쓰기 비율 높음, 일관성 요구 낮음 |
| **사용자 정보** | Cache Aside | Write Through | 일관성 중요, 업데이트 후 즉시 조회 |
| **주문 조회** | 미적용 | Cache Aside | 읽기 중심, 조회 빈번 |
| **브랜드 정보** | 미적용 | Cache Aside (로컬) | 마스터 데이터, 변경 빈도 낮음 |

---

## 주의사항

### 1. 트랜잭션과 캐시 일관성

- **문제**: 트랜잭션 롤백 시 캐시는 이미 업데이트됨
- **해결**: 트랜잭션 커밋 후에만 캐시 업데이트 (`@TransactionalEventListener` 사용)

### 2. 캐시 장애 처리

- **Cache Aside**: DB로 폴백 가능
- **Write Through**: 쓰기 실패 처리 필요
- **Write Back**: 데이터 손실 방지 전략 필요 (재시도 큐, 영속화)

### 3. 분산 환경에서의 일관성

- **로컬 캐시**: 인스턴스별 캐시 불일치 가능
- **Redis 캐시**: 분산 캐시로 일관성 보장
- **캐시 무효화**: 이벤트 기반 무효화 고려 (Kafka 등)

---

## 참고 자료

- [Cache-Aside Pattern](https://docs.microsoft.com/en-us/azure/architecture/patterns/cache-aside)
- [Write-Through Pattern](https://docs.microsoft.com/en-us/azure/architecture/patterns/cache-aside)
- [Write-Behind Pattern](https://docs.oracle.com/cd/E15357_01/coh.360/e15723/cache_rtwtwbra.htm#COHDG5177)

