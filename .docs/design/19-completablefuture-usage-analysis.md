# CompletableFuture 사용 가능성 분석

## 📌 개요

본 문서는 프로젝트에서 CompletableFuture를 사용하여 성능을 개선할 수 있는 부분을 분석합니다.

---

## 🔍 현재 상태

### CompletableFuture 사용 여부

**확인 결과**: ❌ **사용하지 않음**

프로젝트 전체에서 CompletableFuture 사용이 없습니다.

---

## 🎯 비동기 처리 개선 가능 영역 분석

### 1. LikeFacade.getLikedProducts() - 상품 정보 조회

#### 현재 구현 (순차 처리)

```java
@Transactional(readOnly = true)
public List<LikedProduct> getLikedProducts(String userId) {
    User user = loadUser(userId);
    
    // 사용자의 좋아요 목록 조회
    List<Like> likes = likeRepository.findAllByUserId(user.getId());
    
    if (likes.isEmpty()) {
        return List.of();
    }
    
    // 상품 ID 목록 추출
    List<Long> productIds = likes.stream()
        .map(Like::getProductId)
        .toList();
    
    // ⚠️ 순차적으로 상품 정보 조회
    List<Product> products = productIds.stream()
        .map(productId -> productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId))))
        .toList();
    
    // 좋아요 수 집계
    Map<Long, Long> likesCountMap = likeRepository.countByProductIds(productIds);
    
    // 좋아요 목록을 상품 정보와 좋아요 수와 함께 변환
    return likes.stream()
        .map(like -> {
            Product product = products.stream()
                .filter(p -> p.getId().equals(like.getProductId()))
                .findFirst()
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", like.getProductId())));
            Long likesCount = likesCountMap.getOrDefault(like.getProductId(), 0L);
            return LikedProduct.from(product, like, likesCount);
        })
        .toList();
}
```

#### 문제점

1. **순차 조회**: 상품 정보를 하나씩 순차적으로 조회
2. **성능 저하**: 좋아요한 상품이 많을수록 조회 시간 증가
3. **DB 연결 시간 낭비**: 각 조회마다 DB 연결 시간 소요

#### 개선 방안: CompletableFuture 사용

```java
@Transactional(readOnly = true)
public List<LikedProduct> getLikedProducts(String userId) {
    User user = loadUser(userId);
    
    // 사용자의 좋아요 목록 조회
    List<Like> likes = likeRepository.findAllByUserId(user.getId());
    
    if (likes.isEmpty()) {
        return List.of();
    }
    
    // 상품 ID 목록 추출
    List<Long> productIds = likes.stream()
        .map(Like::getProductId)
        .toList();
    
    // ✅ 병렬로 상품 정보 조회
    List<CompletableFuture<Product>> productFutures = productIds.stream()
        .map(productId -> CompletableFuture.supplyAsync(() ->
            productRepository.findById(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId))),
            executorService))
        .toList();
    
    // ✅ 병렬로 좋아요 수 집계
    CompletableFuture<Map<Long, Long>> likesCountFuture = CompletableFuture.supplyAsync(() ->
        likeRepository.countByProductIds(productIds),
        executorService);
    
    // 모든 작업 완료 대기
    CompletableFuture.allOf(
        productFutures.toArray(new CompletableFuture[0])
    ).join();
    
    List<Product> products = productFutures.stream()
        .map(CompletableFuture::join)
        .toList();
    
    Map<Long, Long> likesCountMap = likesCountFuture.join();
    
    // 좋아요 목록을 상품 정보와 좋아요 수와 함께 변환
    return likes.stream()
        .map(like -> {
            Product product = products.stream()
                .filter(p -> p.getId().equals(like.getProductId()))
                .findFirst()
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", like.getProductId())));
            Long likesCount = likesCountMap.getOrDefault(like.getProductId(), 0L);
            return LikedProduct.from(product, like, likesCount);
        })
        .toList();
}
```

#### ⚠️ 주의사항

1. **트랜잭션 경계**: `@Transactional(readOnly = true)` 내에서 CompletableFuture 사용 시 트랜잭션 컨텍스트가 전파되지 않음
2. **해결 방법**: 
   - 트랜잭션 밖에서 비동기 처리
   - 또는 `@Async` 사용 (Spring의 비동기 지원)

#### 개선된 구현 (Spring @Async 사용)

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.initialize();
        return executor;
    }
}

@Service
public class ProductService {
    @Async
    public CompletableFuture<Product> findProductById(Long productId) {
        return CompletableFuture.completedFuture(
            productRepository.findById(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId)))
        );
    }
    
    @Async
    public CompletableFuture<Map<Long, Long>> countLikesByProductIds(List<Long> productIds) {
        return CompletableFuture.completedFuture(
            likeRepository.countByProductIds(productIds)
        );
    }
}

@Transactional(readOnly = true)
public List<LikedProduct> getLikedProducts(String userId) {
    User user = loadUser(userId);
    
    List<Like> likes = likeRepository.findAllByUserId(user.getId());
    
    if (likes.isEmpty()) {
        return List.of();
    }
    
    List<Long> productIds = likes.stream()
        .map(Like::getProductId)
        .toList();
    
    // ✅ 병렬로 상품 정보 조회
    List<CompletableFuture<Product>> productFutures = productIds.stream()
        .map(productService::findProductById)
        .toList();
    
    // ✅ 병렬로 좋아요 수 집계
    CompletableFuture<Map<Long, Long>> likesCountFuture = 
        productService.countLikesByProductIds(productIds);
    
    // 모든 작업 완료 대기
    CompletableFuture.allOf(
        productFutures.toArray(new CompletableFuture[0])
    ).join();
    
    List<Product> products = productFutures.stream()
        .map(CompletableFuture::join)
        .toList();
    
    Map<Long, Long> likesCountMap = likesCountFuture.join();
    
    // 변환 로직...
}
```

#### 성능 개선 효과

| 항목 | 순차 처리 | 병렬 처리 (CompletableFuture) |
|------|----------|------------------------------|
| **조회 시간 (10개 상품)** | 100ms (10ms × 10) | 10ms (병렬 처리) |
| **조회 시간 (100개 상품)** | 1000ms (10ms × 100) | 10ms (병렬 처리) |
| **성능 개선** | - | **10배 ~ 100배** |

---

### 2. PurchasingFacade.createOrder() - 사용자/상품 조회

#### 현재 구현

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 사용자 조회 (비관적 락)
    User user = loadUserForUpdate(userId);
    
    // 2. 상품 조회 (비관적 락, 순차 처리)
    for (OrderItemCommand command : commands) {
        Product product = productRepository.findByIdForUpdate(command.productId());
        // ...
    }
    
    // ...
}
```

#### 분석

**⚠️ 비관적 락이 필요한 경우 병렬 처리 불가**:
- 사용자 조회와 상품 조회는 독립적이지만, 비관적 락이 필요하므로 순차 처리 필요
- 여러 상품 조회도 비관적 락이 필요하므로 순차 처리 필요

**✅ 개선 가능한 부분**:
- 사용자 조회와 첫 번째 상품 조회는 병렬 처리 가능 (독립적)
- 하지만 락 획득 순서 일관성 유지 필요

#### 개선 방안 (제한적)

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // ✅ 사용자 조회와 첫 번째 상품 조회를 병렬로 처리
    CompletableFuture<User> userFuture = CompletableFuture.supplyAsync(() ->
        loadUserForUpdate(userId), executorService);
    
    CompletableFuture<Product> firstProductFuture = CompletableFuture.supplyAsync(() ->
        productRepository.findByIdForUpdate(commands.get(0).productId())
            .orElseThrow(...), executorService);
    
    // 나머지 상품은 순차 처리 (비관적 락 필요)
    User user = userFuture.join();
    Product firstProduct = firstProductFuture.join();
    
    // 나머지 상품 조회...
}
```

#### ⚠️ 주의사항

1. **락 순서 일관성**: 데드락 방지를 위해 락 획득 순서 일관성 유지 필요
2. **트랜잭션 경계**: 비관적 락은 트랜잭션 내에서만 유효
3. **성능 개선 효과 제한적**: 락 경쟁이 있는 경우 병렬 처리 효과 제한적

**결론**: **비관적 락이 필요한 경우 CompletableFuture 사용 효과 제한적**

---

### 3. 외부 API 호출 (현재 없음)

#### 현재 상태

프로젝트에 외부 API 호출이 없습니다.

#### 향후 추가 시 개선 방안

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 주문 생성 로직...
    
    // ✅ 외부 API 호출을 비동기로 처리
    CompletableFuture<Void> notificationFuture = CompletableFuture.runAsync(() -> {
        externalNotificationService.notifyOrderCreated(order);
    }, executorService);
    
    // 주문 정보 반환 (외부 API 호출 완료 대기 안 함)
    return OrderInfo.from(order);
    
    // 필요시 나중에 완료 확인
    // notificationFuture.join(); // 또는 예외 처리
}
```

---

## 📊 종합 평가

### CompletableFuture 사용 가능 영역

| 영역 | 현재 상태 | 개선 가능성 | 우선순위 | 주의사항 |
|------|----------|------------|---------|---------|
| **LikeFacade.getLikedProducts()** | 순차 조회 | ✅ 높음 | ⭐⭐⭐ | 트랜잭션 경계 주의 |
| **PurchasingFacade.createOrder()** | 순차 조회 | ⚠️ 제한적 | ⭐ | 비관적 락 필요 |
| **외부 API 호출** | 없음 | ✅ 높음 | ⭐⭐ | 향후 추가 시 |

### 성능 개선 효과 예상

| 개선 영역 | 현재 성능 | 개선 후 성능 | 개선율 |
|----------|----------|------------|--------|
| **좋아요 상품 조회 (10개)** | 100ms | 10ms | **10배** |
| **좋아요 상품 조회 (100개)** | 1000ms | 10ms | **100배** |
| **주문 생성** | 제한적 | 제한적 | **미미** |

---

## 💡 권장 사항

### 1. 즉시 적용 가능: LikeFacade.getLikedProducts()

**이유**:
- 읽기 전용 트랜잭션
- 비관적 락 불필요
- 병렬 처리 효과 큼

**구현 방법**:
- Spring `@Async` 사용
- 또는 CompletableFuture 직접 사용 (트랜잭션 경계 주의)

### 2. 제한적 적용: PurchasingFacade.createOrder()

**이유**:
- 비관적 락 필요
- 락 순서 일관성 유지 필요
- 성능 개선 효과 제한적

**권장**:
- 현재 구조 유지
- Hot Spot 발생 시 다른 전략 고려 (Optimistic Lock, Queueing)

### 3. 향후 고려: 외부 API 호출

**이유**:
- 외부 API 호출은 느림
- 비동기 처리로 응답 시간 단축 가능

**권장**:
- 외부 API 호출 추가 시 CompletableFuture 사용

---

## 🎯 구현 예시

### LikeFacade.getLikedProducts() 개선

```java
@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final LikeRepository likeRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ExecutorService executorService;
    
    @Transactional(readOnly = true)
    public List<LikedProduct> getLikedProducts(String userId) {
        User user = loadUser(userId);
        
        List<Like> likes = likeRepository.findAllByUserId(user.getId());
        
        if (likes.isEmpty()) {
            return List.of();
        }
        
        List<Long> productIds = likes.stream()
            .map(Like::getProductId)
            .toList();
        
        // ✅ 병렬로 상품 정보 조회
        List<CompletableFuture<Product>> productFutures = productIds.stream()
            .map(productId -> CompletableFuture.supplyAsync(() ->
                productRepository.findById(productId)
                    .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                        String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId))),
                executorService))
            .toList();
        
        // ✅ 병렬로 좋아요 수 집계
        CompletableFuture<Map<Long, Long>> likesCountFuture = CompletableFuture.supplyAsync(() ->
            likeRepository.countByProductIds(productIds),
            executorService);
        
        // 모든 작업 완료 대기
        CompletableFuture.allOf(
            productFutures.toArray(new CompletableFuture[0])
        ).join();
        
        List<Product> products = productFutures.stream()
            .map(CompletableFuture::join)
            .toList();
        
        Map<Long, Long> likesCountMap = likesCountFuture.join();
        
        // 변환 로직...
        return likes.stream()
            .map(like -> {
                Product product = products.stream()
                    .filter(p -> p.getId().equals(like.getProductId()))
                    .findFirst()
                    .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                        String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", like.getProductId())));
                Long likesCount = likesCountMap.getOrDefault(like.getProductId(), 0L);
                return LikedProduct.from(product, like, likesCount);
            })
            .toList();
    }
}
```

### ExecutorService 설정

```java
@Configuration
public class AsyncConfig {
    @Bean
    public ExecutorService executorService() {
        return Executors.newFixedThreadPool(
            10, // 스레드 풀 크기
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "async-db-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );
    }
}
```

---

## ⚠️ 주의사항

### 1. 트랜잭션 경계

**문제**:
- `@Transactional` 내에서 CompletableFuture 사용 시 트랜잭션 컨텍스트가 전파되지 않음
- 비동기 작업은 별도의 트랜잭션에서 실행됨

**해결**:
- 읽기 전용 작업은 문제 없음
- 쓰기 작업은 트랜잭션 경계를 명확히 해야 함

### 2. 예외 처리

**문제**:
- CompletableFuture에서 발생한 예외는 `join()` 시에만 확인 가능

**해결**:
```java
CompletableFuture<Product> future = CompletableFuture.supplyAsync(() -> {
    return productRepository.findById(productId)
        .orElseThrow(...);
}, executorService);

try {
    Product product = future.join();
} catch (CompletionException e) {
    // 예외 처리
    throw new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다.");
}
```

### 3. 리소스 관리

**문제**:
- ExecutorService를 적절히 관리하지 않으면 리소스 누수 발생

**해결**:
- Spring Bean으로 관리
- 애플리케이션 종료 시 shutdown 처리

---

## 🔗 관련 문서

- [동시성 처리 설계 원칙](./15-concurrency-design-principles.md)
- [Hot Spot 문제와 비관적 락의 한계](./18-hotspot-pessimistic-lock-limitations.md)
- [WAL 성능 평가](./14-wal-performance-evaluation.md)

