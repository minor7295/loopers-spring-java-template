# 좋아요 설계 옵션 비교 및 재설계

## 📌 개요

좋아요 기능을 구현하는 방법은 여러 가지가 있으며, 각각의 트레이드오프가 있습니다. 본 문서는 다양한 설계 옵션을 비교하고, 현재 프로젝트에 적용 가능한 방안을 제시합니다.

---

## 🎯 설계 옵션 비교

### 옵션 1: 컬럼 기반 좋아요 (Column-Based)

#### 구조
```
Product 테이블
- id
- name
- price
- stock
- like_count  ← 좋아요 수를 컬럼으로 저장
```

#### 구현 예시

##### 1-1. 비관적 락 버전

```java
@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    private Integer price;
    private Integer stock;
    
    @Column(name = "like_count", nullable = false)
    private Long likeCount = 0L;
    
    public void incrementLikeCount() {
        this.likeCount++;
    }
    
    public void decrementLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }
}

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :productId")
    Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
}

@Transactional
public void addLike(String userId, Long productId) {
    // 비관적 락으로 상품 조회
    Product product = productRepository.findByIdForUpdate(productId)
        .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
    
    // 중복 체크 (별도 테이블 필요)
    if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
        return; // 이미 좋아요 함
    }
    
    // 좋아요 수 증가
    product.incrementLikeCount();
    productRepository.save(product);
    
    // 좋아요 기록 저장 (중복 체크용)
    Like like = Like.of(userId, productId);
    likeRepository.save(like);
}
```

**장점**:
- ✅ 조회 성능 우수: `SELECT * FROM product WHERE id = 1` 한 번만 조회
- ✅ 구현 간단: 컬럼 하나 추가, +1 업데이트

**단점**:
- ❌ **쓰기 경합**: 하나의 게시물 row에 쓰기 경합이 몰림 → 락 경쟁
- ❌ **중복 체크 불가**: 같은 회원이 여러 번 눌러도 컬럼만으로는 중복 체크 불가 (별도 테이블 필요)
- ❌ **동시성 문제**: 비관적 락 사용 시 대기 시간 증가

##### 1-2. 낙관적 락 버전

```java
@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    private Integer price;
    private Integer stock;
    
    @Column(name = "like_count", nullable = false)
    private Long likeCount = 0L;
    
    @Version  // 낙관적 락을 위한 버전 컬럼
    private Long version;
    
    public void incrementLikeCount() {
        this.likeCount++;
    }
}

@Transactional
public void addLike(String userId, Long productId) {
    int maxRetries = 3;
    int retryCount = 0;
    
    while (retryCount < maxRetries) {
        try {
            // 낙관적 락으로 상품 조회
            Product product = productRepository.findById(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
            
            // 중복 체크
            if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
                return;
            }
            
            // 좋아요 수 증가 (CAS: Compare-And-Swap)
            product.incrementLikeCount();
            productRepository.save(product);  // version 체크 후 업데이트
            
            // 좋아요 기록 저장
            Like like = Like.of(userId, productId);
            likeRepository.save(like);
            
            return; // 성공
        } catch (OptimisticLockingFailureException e) {
            retryCount++;
            if (retryCount >= maxRetries) {
                throw new CoreException(ErrorType.CONFLICT, "좋아요 처리 중 충돌이 발생했습니다. 다시 시도해주세요.");
            }
            // 재시도 전 짧은 대기
            try {
                Thread.sleep(10 + (retryCount * 10)); // 10ms, 20ms, 30ms
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new CoreException(ErrorType.INTERNAL_ERROR, "좋아요 처리 중 중단되었습니다.");
            }
        }
    }
}
```

**장점**:
- ✅ 락 경쟁 감소: 비관적 락보다 대기 시간 적음
- ✅ 조회 성능 우수: 컬럼만 읽으면 됨

**단점**:
- ❌ **재시도 로직 필요**: OptimisticLockingFailureException 처리 필요
- ❌ **일부 실패 가능**: 재시도 횟수 초과 시 실패
- ❌ **중복 체크 불가**: 여전히 별도 테이블 필요

---

### 옵션 2: 테이블 분리 기반 좋아요 (Table-Based) ⭐ 현재 프로젝트

#### 구조
```
Product 테이블
- id
- name
- price
- stock

Like 테이블 (별도)
- id
- ref_user_id
- ref_product_id
- created_at
- UNIQUE(ref_user_id, ref_product_id)
```

#### 구현 예시

```java
@Entity
@Table(
    name = "`like`",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_like_user_product",
            columnNames = {"ref_user_id", "ref_product_id"}
        )
    }
)
public class Like {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "ref_user_id", nullable = false)
    private Long userId;
    
    @Column(name = "ref_product_id", nullable = false)
    private Long productId;
}

@Transactional
public void addLike(String userId, Long productId) {
    User user = loadUser(userId);
    loadProduct(productId);
    
    // 중복 체크
    Optional<Like> existingLike = likeRepository.findByUserIdAndProductId(user.getId(), productId);
    if (existingLike.isPresent()) {
        return;
    }
    
    // Insert-only → 경합 거의 없음
    Like like = Like.of(user.getId(), productId);
    try {
        likeRepository.save(like);
    } catch (DataIntegrityViolationException e) {
        // UNIQUE 제약조건 위반 예외 처리 (멱등성 보장)
        return;
    }
}

// 조회 시 COUNT(*) 필요
public Long getLikeCount(Long productId) {
    return likeRepository.countByProductId(productId);
}
```

**장점**:
- ✅ **쓰기 경합 최소화**: Insert-only → 경합 거의 없음
- ✅ **중복 체크 가능**: UNIQUE 제약조건으로 자동 방지
- ✅ **회원별 좋아요 기록 저장**: 사용자가 좋아요한 상품 목록 조회 가능
- ✅ **확장성**: 좋아요 시간, 취소 여부 등 추가 정보 저장 가능

**단점**:
- ❌ **조회 병목**: 조회 시마다 `COUNT(*)` 필요
- ❌ **성능 저하**: 대량 조회 시 집계 쿼리 부하

---

### 옵션 3: 하이브리드 방식 (Eventually Consistent) ⭐ 권장

#### 구조
```
Product 테이블
- id
- name
- price
- stock
- like_count  ← 캐시된 좋아요 수

Like 테이블 (별도)
- id
- ref_user_id
- ref_product_id
- created_at
- UNIQUE(ref_user_id, ref_product_id)

Redis (선택)
- product:{id}:like_count
```

#### 구현 예시

```java
@Transactional
public void addLike(String userId, Long productId) {
    User user = loadUser(userId);
    loadProduct(productId);
    
    // Like 테이블에 Insert-only (쓰기 경합 없음)
    Like like = Like.of(user.getId(), productId);
    try {
        likeRepository.save(like);
    } catch (DataIntegrityViolationException e) {
        // 이미 좋아요 함
        return;
    }
    
    // 비동기로 like_count 업데이트 (선택적)
    // 스케줄러가 주기적으로 COUNT(*) 해서 업데이트
}

// 스케줄러 (매 1초 또는 5초마다 실행)
@Scheduled(fixedDelay = 1000) // 1초마다
public void updateLikeCounts() {
    // 최근 업데이트된 상품들만 조회
    List<Long> recentlyUpdatedProductIds = getRecentlyUpdatedProductIds();
    
    for (Long productId : recentlyUpdatedProductIds) {
        // COUNT(*)로 실제 좋아요 수 계산
        Long actualCount = likeRepository.countByProductId(productId);
        
        // Product 테이블의 like_count 업데이트
        productRepository.updateLikeCount(productId, actualCount);
        
        // Redis 캐시 업데이트 (선택적)
        redisTemplate.opsForValue().set("product:" + productId + ":like_count", actualCount);
    }
}

// 조회는 빠르게
public Long getLikeCount(Long productId) {
    // Redis에서 먼저 조회
    Long cachedCount = redisTemplate.opsForValue().get("product:" + productId + ":like_count");
    if (cachedCount != null) {
        return cachedCount;
    }
    
    // Redis에 없으면 DB에서 조회
    Product product = productRepository.findById(productId)
        .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
    return product.getLikeCount();
}
```

**장점**:
- ✅ **쓰기 경합 없음**: Insert-only로 Like 테이블에 저장
- ✅ **조회 성능 우수**: like_count 컬럼 또는 Redis 캐시 사용
- ✅ **중복 체크 가능**: UNIQUE 제약조건으로 자동 방지
- ✅ **확장성**: 대규모 트래픽 처리 가능

**단점**:
- ❌ **약간의 지연**: 좋아요 수가 1초 정도 지연될 수 있음
- ❌ **구현 복잡도**: 스케줄러 및 캐시 관리 필요

---

## 📊 비교표

| 항목 | 컬럼 기반 (비관적 락) | 컬럼 기반 (낙관적 락) | 테이블 분리 | 하이브리드 |
|------|---------------------|---------------------|------------|-----------|
| **구현 복잡도** | ⭐⭐ 간단 | ⭐⭐⭐ 중간 | ⭐⭐ 간단 | ⭐⭐⭐⭐ 복잡 |
| **쓰기 성능** | ❌ 락 경쟁 심함 | ⚠️ 재시도 필요 | ✅ Insert-only | ✅ Insert-only |
| **조회 성능** | ✅ 매우 빠름 | ✅ 매우 빠름 | ❌ COUNT(*) 필요 | ✅ 매우 빠름 |
| **중복 체크** | ❌ 별도 테이블 필요 | ❌ 별도 테이블 필요 | ✅ UNIQUE 제약조건 | ✅ UNIQUE 제약조건 |
| **동시성 처리** | ⚠️ 락 대기 | ⚠️ 재시도 | ✅ 경합 없음 | ✅ 경합 없음 |
| **정확성** | ✅ 즉시 반영 | ✅ 즉시 반영 | ✅ 즉시 반영 | ⚠️ 약간의 지연 |
| **확장성** | ❌ 낮음 | ⚠️ 중간 | ✅ 높음 | ✅ 매우 높음 |

---

## 🎯 현재 프로젝트 분석

### 현재 구조: 테이블 분리 기반

**현재 구현**:
- ✅ Like 테이블 분리
- ✅ UNIQUE 제약조건으로 중복 방지
- ✅ Insert-only로 쓰기 경합 최소화
- ❌ 조회 시 `COUNT(*)` 필요 (성능 이슈)

**개선 방안**:

#### 방안 1: 하이브리드 방식으로 전환 (권장)

```java
// 1. Product 테이블에 like_count 컬럼 추가
@Entity
public class Product {
    // ... 기존 필드들
    
    @Column(name = "like_count", nullable = false)
    private Long likeCount = 0L;
}

// 2. Like 테이블은 그대로 유지 (중복 체크 및 기록용)

// 3. 스케줄러로 주기적으로 동기화
@Scheduled(fixedDelay = 5000) // 5초마다
public void syncLikeCounts() {
    // 최근 좋아요가 추가/삭제된 상품들만 동기화
    List<Long> productIds = getRecentlyUpdatedProductIds();
    
    for (Long productId : productIds) {
        Long actualCount = likeRepository.countByProductId(productId);
        productRepository.updateLikeCount(productId, actualCount);
    }
}
```

**장점**:
- ✅ 쓰기 경합 없음 (Insert-only 유지)
- ✅ 조회 성능 향상 (컬럼만 읽으면 됨)
- ✅ 중복 체크 가능 (UNIQUE 제약조건 유지)

**단점**:
- ⚠️ 약간의 지연 (5초 정도)

#### 방안 2: Redis 캐시 추가

```java
// Redis에 좋아요 수 캐싱
public Long getLikeCount(Long productId) {
    String cacheKey = "product:" + productId + ":like_count";
    
    // Redis에서 먼저 조회
    Long cachedCount = redisTemplate.opsForValue().get(cacheKey);
    if (cachedCount != null) {
        return cachedCount;
    }
    
    // Redis에 없으면 DB에서 조회 후 캐시
    Long actualCount = likeRepository.countByProductId(productId);
    redisTemplate.opsForValue().set(cacheKey, actualCount, 60, TimeUnit.SECONDS);
    
    return actualCount;
}

// 좋아요 추가 시 Redis도 업데이트
@Transactional
public void addLike(String userId, Long productId) {
    // ... 기존 로직
    
    // Redis 캐시 무효화 또는 증가
    String cacheKey = "product:" + productId + ":like_count";
    redisTemplate.delete(cacheKey); // 또는 INCR 사용
}
```

---

## 💡 설계 철학: Eventually Consistent vs Strong Consistency

### 좋아요 수: Eventually Consistent 가능

**이유**:
- 좋아요 수는 **약간의 지연을 허용할 수 있는 데이터**
- 사용자에게 1초 정도의 지연은 큰 문제가 되지 않음
- 정확성보다 **성능과 확장성**이 더 중요

**적용**:
- 하이브리드 방식 사용
- 스케줄러로 주기적 동기화
- Redis 캐시로 조회 성능 향상

### 주문/포인트: Strong Consistency 필수

**이유**:
- 주문과 포인트는 **돈과 직접 연결된 값**
- 즉시 정확한 값이 필요함
- 정확성이 **성능보다 우선**

**적용**:
- 강한 트랜잭션 사용
- 비관적 락으로 동시성 제어
- 즉시 반영 보장

### 비교표

| 데이터 | 정확성 요구도 | 지연 허용 | 적용 방식 |
|--------|-------------|----------|----------|
| **좋아요 수** | 낮음 | ✅ 허용 가능 | Eventually Consistent |
| **주문 금액** | 매우 높음 | ❌ 불가 | Strong Consistency |
| **포인트 잔액** | 매우 높음 | ❌ 불가 | Strong Consistency |
| **재고 수량** | 높음 | ❌ 불가 | Strong Consistency |
| **조회 수** | 낮음 | ✅ 허용 가능 | Eventually Consistent |

---

## 🎯 락 전략 비교: 애플리케이션 락 vs DB 락

### 1. synchronized (애플리케이션 락) ❌ **문제점**

#### 구현 예시

```java
@Component
public class LikeFacade {
    private final ProductRepository productRepository;
    
    /**
     * ❌ 잘못된 구현: synchronized 사용
     * 
     * 문제점:
     * 1. 트랜잭션 경계 바깥에서 락을 거는 바람에 동작이 깨짐
     * 2. 단일 인스턴스에서만 동작 (분산 환경 불가)
     * 3. 트랜잭션 커밋 전에 락이 해제됨
     */
    @Transactional
    public synchronized void addLike(String userId, Long productId) {
        // 1. 락 획득 (synchronized)
        // 2. Product 조회
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        
        // 3. 좋아요 수 증가
        product.incrementLikeCount();
        productRepository.save(product);
        
        // 4. 트랜잭션 커밋 전에 synchronized 락 해제!
        // → 다른 스레드가 아직 커밋되지 않은 값을 읽을 수 있음
    }
}
```

#### 문제 상황: 트랜잭션 경계와 락 범위 불일치

**타임라인**:
```
T1 (Thread-1):
1. synchronized 락 획득
2. Product 조회 (like_count = 100)
3. like_count = 101로 증가
4. save() 호출 (아직 커밋 안 됨)
5. synchronized 락 해제 ← 문제! 트랜잭션 커밋 전에 락 해제
6. 트랜잭션 커밋 (나중에)

T2 (Thread-2):
1. synchronized 락 획득 (T1이 락 해제 후)
2. Product 조회 (like_count = 100) ← T1의 변경사항 아직 안 보임!
3. like_count = 101로 증가
4. save() 호출
5. synchronized 락 해제
6. 트랜잭션 커밋

결과: Lost Update 발생! (like_count = 101이 되어야 하는데 101로 저장됨)
```

#### 실험 코드

```java
@Test
void synchronizedTest_transactionBoundaryIssue() throws InterruptedException {
    // arrange
    Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());
    Long productId = product.getId();
    
    int threadCount = 100;
    ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();
    
    // act
    for (int i = 0; i < threadCount; i++) {
        final int userId = i;
        executorService.submit(() -> {
            try {
                // synchronized를 사용한 잘못된 구현
                likeFacade.addLike("user" + userId, productId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                synchronized (exceptions) {
                    exceptions.add(e);
                }
            } finally {
                latch.countDown();
            }
        });
    }
    latch.await();
    executorService.shutdown();
    
    // assert
    Product savedProduct = productRepository.findById(productId).orElseThrow();
    
    // ❌ 예상: 100, 실제: 50~100 사이의 값 (Lost Update 발생)
    // synchronized 락이 트랜잭션 커밋 전에 해제되어 동시성 문제 발생
    assertThat(savedProduct.getLikeCount()).isLessThan(100); // 실패!
}
```

**결과**: 
- ❌ **Lost Update 발생**: synchronized 락이 트랜잭션 커밋 전에 해제됨
- ❌ **단일 인스턴스에서만 동작**: 분산 환경에서는 완전히 무용지물
- ❌ **트랜잭션 경계와 락 범위 불일치**: 락 범위 < 트랜잭션 범위

#### 왜 synchronized로 해결되면 안 되는가?

1. **트랜잭션 경계 바깥에서 락**: 
   - synchronized는 메서드 실행 동안만 락 유지
   - 트랜잭션은 메서드 종료 후에도 커밋까지 유지
   - 락 해제 시점 < 트랜잭션 커밋 시점

2. **단일 인스턴스 제한**:
   - JVM 내부에서만 동작
   - 분산 환경(여러 서버)에서는 각 서버마다 별도의 락
   - 동시성 제어 불가

3. **DB 레벨 동시성 제어 불가**:
   - DB 트랜잭션과 무관하게 동작
   - DB 레벨의 Lost Update 방지 불가

---

### 2. Redis 분산 락 (분산 환경 대응) ⚠️ **복잡함**

#### 구현 예시

```java
@Component
public class LikeFacade {
    private final RedisTemplate<String, String> redisTemplate;
    private final ProductRepository productRepository;
    
    /**
     * Redis 분산 락을 사용한 구현
     * 
     * 장점:
     * - 분산 환경에서 동작
     * - 여러 서버 간 동시성 제어 가능
     * 
     * 단점:
     * - 구현 복잡도 증가
     * - Redis 장애 시 전체 시스템 영향
     * - 락 만료 시간 관리 필요
     */
    @Transactional
    public void addLike(String userId, Long productId) {
        String lockKey = "lock:product:" + productId;
        String lockValue = UUID.randomUUID().toString();
        long lockExpireTime = 10; // 10초
        
        // 1. Redis 분산 락 획득 시도
        Boolean lockAcquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(lockExpireTime));
        
        if (!lockAcquired) {
            // 락 획득 실패 → 재시도 또는 예외
            throw new CoreException(ErrorType.CONFLICT, 
                "다른 사용자가 처리 중입니다. 잠시 후 다시 시도해주세요.");
        }
        
        try {
            // 2. 락 획득 성공 → 비즈니스 로직 실행
            Product product = productRepository.findById(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, 
                    "상품을 찾을 수 없습니다."));
            
            product.incrementLikeCount();
            productRepository.save(product);
            
        } finally {
            // 3. 락 해제 (Lua 스크립트로 안전하게)
            String script = 
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) " +
                "else return 0 end";
            redisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(lockKey),
                lockValue
            );
        }
    }
}
```

#### 문제점

1. **구현 복잡도**:
   - 락 획득/해제 로직 필요
   - 락 만료 시간 관리 필요
   - Lua 스크립트로 안전한 락 해제 필요

2. **Redis 의존성**:
   - Redis 장애 시 전체 시스템 영향
   - 네트워크 지연 추가

3. **트랜잭션 경계와 락 범위 불일치**:
   - 여전히 트랜잭션 커밋 전에 락 해제 가능
   - DB 트랜잭션과 Redis 락의 생명주기 불일치

#### 분산 환경에서 synchronized 문제 해결

**synchronized의 한계**:
```
서버 1 (JVM-1): synchronized 락 → 서버 1 내부에서만 동작
서버 2 (JVM-2): synchronized 락 → 서버 2 내부에서만 동작
서버 3 (JVM-3): synchronized 락 → 서버 3 내부에서만 동작

→ 각 서버마다 별도의 락 → 동시성 제어 불가!
```

**Redis 분산 락의 해결**:
```
서버 1: Redis 락 획득 시도 → Redis에서 확인
서버 2: Redis 락 획득 시도 → Redis에서 확인 (이미 락 있음 → 대기)
서버 3: Redis 락 획득 시도 → Redis에서 확인 (이미 락 있음 → 대기)

→ 모든 서버가 같은 Redis를 바라봄 → 분산 환경에서도 동작!
```

---

### 3. DB Record Lock (SELECT ... FOR UPDATE) ✅ **권장**

#### 구현 예시

```java
@Repository
public interface ProductJpaRepository extends JpaRepository<Product, Long> {
    /**
     * DB Record Lock 사용
     * 
     * 장점:
     * - 트랜잭션 경계와 락 범위 일치
     * - DB 레벨에서 동시성 제어
     * - 분산 환경에서도 자동 동작
     * - 구현 간단
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :productId")
    Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
}

@Component
public class LikeFacade {
    private final ProductRepository productRepository;
    
    /**
     * ✅ 올바른 구현: DB Record Lock 사용
     * 
     * 장점:
     * 1. 트랜잭션 경계와 락 범위 일치
     * 2. DB 레벨에서 동시성 제어
     * 3. 분산 환경에서도 자동 동작
     */
    @Transactional
    public void addLike(String userId, Long productId) {
        // 1. SELECT ... FOR UPDATE → DB 레벨 락 획득
        Product product = productRepository.findByIdForUpdate(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, 
                "상품을 찾을 수 없습니다."));
        
        // 2. 락이 유지되는 동안 데이터 수정
        product.incrementLikeCount();
        productRepository.save(product);
        
        // 3. 트랜잭션 커밋 시 락 해제
        // → 락 범위 = 트랜잭션 범위 ✅
    }
}
```

#### 동작 원리

**타임라인**:
```
T1 (서버 1):
1. SELECT ... FOR UPDATE → DB 레벨 락 획득
2. Product 조회 (like_count = 100)
3. like_count = 101로 증가
4. save() 호출
5. 트랜잭션 커밋 → 락 해제

T2 (서버 2):
1. SELECT ... FOR UPDATE → DB에서 락 대기 (T1이 락 해제할 때까지)
2. T1 커밋 후 락 획득
3. Product 조회 (like_count = 101) ← T1의 변경사항 반영됨!
4. like_count = 102로 증가
5. save() 호출
6. 트랜잭션 커밋 → 락 해제

결과: Lost Update 방지! (like_count = 102로 정확하게 저장됨)
```

#### 장점

1. **트랜잭션 경계와 락 범위 일치**:
   - 락 범위 = 트랜잭션 범위
   - 트랜잭션 커밋 시 락 해제

2. **DB 레벨 동시성 제어**:
   - DB가 직접 관리
   - 애플리케이션 코드 간단

3. **분산 환경 자동 대응**:
   - 모든 서버가 같은 DB를 바라봄
   - 별도 설정 불필요

4. **구현 간단**:
   - `@Lock(LockModeType.PESSIMISTIC_WRITE)` 한 줄 추가
   - 복잡한 락 관리 로직 불필요

---

### 4. 비교표

| 방식 | 트랜잭션 경계 일치 | 분산 환경 | 구현 복잡도 | DB 레벨 제어 | 권장도 |
|------|------------------|----------|------------|------------|--------|
| **synchronized** | ❌ 불일치 | ❌ 불가 | ⭐ 간단 | ❌ 불가 | ❌ 비권장 |
| **Redis 분산 락** | ⚠️ 주의 필요 | ✅ 가능 | ⭐⭐⭐⭐ 복잡 | ❌ 불가 | ⚠️ 특수 상황 |
| **DB Record Lock** | ✅ 일치 | ✅ 자동 | ⭐⭐ 간단 | ✅ 가능 | ✅ **권장** |

---

## 🎯 Database 병목 문제 / 스케일 아웃 한계

### 문제 상황: API 서버 Scale-Out 후 DB 병목

#### 시나리오

```
초기 상태:
- API 서버: 1대
- DB 서버: 1대
- 처리량: 100 req/s

Scale-Out 후:
- API 서버: 10대 (10배 증가)
- DB 서버: 1대 (변화 없음)
- 처리량: 100 req/s (변화 없음!) ← 문제!
```

#### 실험 코드

```java
/**
 * API 서버 Scale-Out 실험
 * 
 * 결과:
 * - API 서버를 10대로 늘려도 처리량이 증가하지 않음
 * - DB가 병목 지점이 됨
 */
@Test
void scaleOutTest_databaseBottleneck() throws InterruptedException {
    // arrange
    Product product = createAndSaveProduct("인기 상품", 10_000, 100, brand.getId());
    Long productId = product.getId();
    
    // API 서버 1대 시뮬레이션
    int serverCount = 1;
    int requestsPerServer = 100;
    int totalRequests = serverCount * requestsPerServer;
    
    ExecutorService executorService = Executors.newFixedThreadPool(totalRequests);
    CountDownLatch latch = new CountDownLatch(totalRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    long startTime = System.currentTimeMillis();
    
    // act
    for (int i = 0; i < totalRequests; i++) {
        executorService.submit(() -> {
            try {
                likeFacade.addLike("user" + i, productId);
                successCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
    }
    latch.await();
    long endTime = System.currentTimeMillis();
    executorService.shutdown();
    
    // assert
    long duration = endTime - startTime;
    double throughput = (double) successCount.get() / (duration / 1000.0);
    
    System.out.println("API 서버 1대:");
    System.out.println("처리량: " + throughput + " req/s");
    System.out.println("소요 시간: " + duration + "ms");
    
    // API 서버 10대 시뮬레이션 (동시 요청 증가)
    // → DB 병목으로 인해 처리량이 크게 증가하지 않음
}
```

#### 병목 분석

**컬럼 기반 방식 (비관적 락)**:
```
API 서버 1대: 100 req/s
API 서버 10대: 100 req/s (변화 없음!)

이유:
- 모든 요청이 같은 Product row를 업데이트
- DB 락 경쟁으로 순차 처리
- API 서버를 늘려도 DB 처리량은 동일
```

**테이블 분리 방식 (Insert-only)**:
```
API 서버 1대: 1,000 req/s
API 서버 10대: 10,000 req/s (10배 증가!)

이유:
- 각 요청이 다른 row에 삽입
- 락 경쟁 없음
- API 서버 증가에 비례하여 처리량 증가
```

#### 스케일 아웃이 안 되는 이유

1. **Hot Spot (핫스팟)**:
   - 인기 상품에 모든 요청이 집중
   - 같은 row를 여러 트랜잭션이 동시에 수정
   - DB 락 경쟁으로 순차 처리

2. **DB 처리량 한계**:
   - DB는 단일 서버 (Scale-Out 어려움)
   - 락 경쟁으로 인한 순차 처리
   - CPU/메모리 증가해도 락 경쟁은 해결 안 됨

3. **수평 확장 불가**:
   - 같은 row를 업데이트해야 함
   - DB 샤딩 불가 (단일 row는 단일 서버에만 존재)
   - 수직 확장만 가능 (더 빠른 DB 서버)

#### 해결 방안

**1. 테이블 분리 방식 (Insert-only)**:
- 각 요청이 다른 row에 삽입
- 락 경쟁 없음
- 수평 확장 가능

**2. 하이브리드 방식**:
- Insert-only로 쓰기 경합 없음
- 스케줄러로 주기적 동기화
- 조회는 캐시된 값 사용

**3. Redis 캐시**:
- 조회 부하 감소
- DB 부하 분산

---

## 📊 트랜잭션 vs 락 vs 동시성 제어 전략 비교표

### 1. 전체 비교 테이블

| 구분 | 트랜잭션(Transaction) | 락(Lock) | 동시성 제어 전략(Concurrency Control) |
|------|---------------------|----------|-------------------------------------|
| **개념** | 여러 작업을 원자성 있게 묶는 DB 기능 | 특정 자원에 대한 접근을 직렬화하는 메커니즘 | 동시 접근 시 정합성과 성능을 모두 고려하는 설계 기법 |
| **목적** | All-or-Nothing 보장, 데이터 무결성 | 공유 자원 보호, 경합 제어 | 높은 트래픽에서 성능 + 정합성 균형 맞추기 |
| **해결하는 문제** | 부분 실패, Dirty Read 등 | Lost Update, Race Condition | DB 병목, 스케일 아웃 실패, 고도화된 경쟁 상황 |
| **한계** | 동시성 문제(Lost Update)는 해결 못함 | 성능/병목 문제 유발 | 설정에 따라 구현 난이도 ↑ |
| **과제 적용 예시** | 주문: 전체 단위 트랜잭션 | 재고: Pessimistic Lock / 쿠폰: Optimistic Lock | Redis Lock, Queueing, Cache, 분산 트랜잭션 등 |

---

### 2. 트랜잭션 내부 비교 (ACID vs Isolation)

| 항목 | 내용 | 과제에서의 의미 |
|------|------|---------------|
| **Atomicity** | 전체 작업 All-or-Nothing | 쿠폰 사용 → 재고 차감 → 포인트 차감이 하나의 트랜잭션이어야 함 |
| **Consistency** | 제약조건 유지 | 재고 < 0, 포인트 음수 불가 |
| **Isolation** | 이상현상 방지 | Repeatable Read여도 Lost Update는 막지 못함 |
| **Durability** | Commit 정보 영구 저장 | 주문 생성 이후 데이터 안정성 보장 |

#### 🔎 핵심 포인트

**Isolation Level은 읽기 이상현상만 해결, 경합은 해결하지 못함**

- `READ COMMITTED`: Dirty Read 방지
- `REPEATABLE READ`: Non-Repeatable Read 방지
- `SERIALIZABLE`: Phantom Read 방지

**하지만 Lost Update는 Isolation Level만으로는 해결 불가!**

```
T1: 재고 조회 (stock = 10)
T2: 재고 조회 (stock = 10)  ← 동시에 같은 값 읽음
T1: 재고 차감 (10 - 3 = 7) → 저장
T2: 재고 차감 (10 - 5 = 5) → 저장  ← T1의 변경사항 손실!

→ Isolation Level이 REPEATABLE READ여도 Lost Update 발생!
→ 그래서 락이 필요함
```

---

### 3. 락(Lock) 전략 비교

| Lock 유형 | 특징 | 장점 | 단점 | 과제 적용 |
|-----------|------|------|------|----------|
| **Pessimistic Lock**<br/>(SELECT … FOR UPDATE) | 먼저 잠그고 실행 | 정합성 가장 확실 | 느림, 병목, Deadlock 위험 | 재고 / 포인트 |
| **Optimistic Lock**<br/>(@Version, CAS) | 충돌 나면 실패 | 성능 좋음, 병목 적음 | 실패 재시도 필요 | 쿠폰 사용 (선착순 쿠폰) |
| **Application Lock**<br/>(synchronized) | JVM 내 락 | 코드단에서 제어 쉬움 | 멀티 서버 불가 | 쿠폰 강의에서 "잘못된 예시" |
| **Distributed Lock**<br/>(Redis) | 여러 서버 간 락 | Scale-out 환경 대응 | Redis 장애/latency에 영향 | 쿠폰 발급 서버 설계 가능 |

#### 🔎 선착순 쿠폰 강의 핵심 요약

1. **synchronized는 트랜잭션 밖에서 락이 걸려서 DB 상태 불일치 발생**
   - 트랜잭션 커밋 전에 락 해제
   - Lost Update 발생

2. **Redis Lock은 애플리케이션 락보다 확실하지만 병목 가능**
   - 분산 환경에서 동작
   - 하지만 Redis 자체가 병목이 될 수 있음

3. **최종적으로 Record Lock + 적절한 인덱스가 실무에서는 가장 안전한 선택**
   - 트랜잭션 경계와 락 범위 일치
   - DB 레벨에서 동시성 제어
   - 분산 환경 자동 대응

---

### 4. 주요 동시성 제어 전략 비교

| 전략 | 설명 | 장점 | 단점 | 적용 도메인 |
|------|------|------|------|------------|
| **트랜잭션 단위로 묶기** | 전체를 하나의 유즈케이스로 처리 | 데이터 정합성 | 동시성 이슈 그대로 존재 | 주문 전체 |
| **비관적 락(Pessimistic)** | 먼저 잠그고 작업 | 정확 | 느리고 병목 | 재고/포인트 |
| **낙관적 락(Optimistic)** | CAS 방식 | 빠름, 스케일 우수 | 실패 처리 필요 | 쿠폰/중복방지 |
| **분산락(Redis RedLock)** | 멀티 서버 락 | 분산 환경 안전 | Redis 병목 | 대규모 트래픽 쿠폰 |
| **Queueing**<br/>(메시지 큐) | 요청을 순차 처리 | 확장성 & 안정성 | 구현 난이도 | 대규모 쿠폰/재고 |
| **Eventually Consistent**<br/>(스케줄러 + 캐시) | 느슨한 정합성 | 읽기 성능 최고 | 즉시 정합성 X | 좋아요 수, 조회수 |

---

### 5. 도메인별 추천 전략 매핑 (과제용)

| 도메인 | 정합성 중요도 | 트래픽 패턴 | 추천 전략 | 이유 |
|--------|-------------|------------|----------|------|
| **주문 생성** | ⭐⭐⭐⭐⭐ | 중간 | 트랜잭션 + 비관적락 일부 | 강한 정합성 필요 |
| **재고 차감** | ⭐⭐⭐⭐⭐ | 높음 | Pessimistic Lock | 1개 재고에 여러 주문이 몰림 |
| **포인트 차감** | ⭐⭐⭐⭐⭐ | 낮음-중간 | Pessimistic Lock | 금융성 데이터 |
| **선착순 쿠폰 사용** | ⭐⭐⭐⭐ | 매우 높음 | Optimistic Lock | 실패 허용 가능, CAS 적합 |
| **선착순 쿠폰 발급(대규모)** | ⭐⭐⭐ | 매우 높음(폭주) | Redis Lock / MQ | DB Lock은 병목 |
| **좋아요/조회수** | ⭐⭐ | 매우 높음 | Eventually Consistent | 약한 정합성 |

#### 현재 프로젝트 적용 현황

**✅ 이미 적용된 전략**:
- **재고 차감**: `PESSIMISTIC_WRITE` (비관적 락)
- **포인트 차감**: `PESSIMISTIC_WRITE` (비관적 락)
- **좋아요**: 테이블 분리 + UNIQUE 제약조건 (Insert-only)

**⚠️ 향후 고려 사항**:
- **쿠폰 사용**: `OPTIMISTIC_LOCK` (낙관적 락)
- **쿠폰 발급**: Redis Lock 또는 Queueing
- **좋아요 수 조회**: Eventually Consistent (하이브리드 방식)

---

### 6. 상황별 "정답 선택 가이드라인"

#### ✔ 정합성이 가장 중요할 때 → Pessimistic Lock

**적용 도메인**: 재고, 포인트

**특징**:
- 은행·결제와 동일한 도메인 성격
- "기다려도 좋으니 절대 틀리면 안 됨"
- Lost Update 방지 최우선

**구현 예시**:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :productId")
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
```

#### ✔ 실패 허용 가능하며 트래픽 높을 때 → Optimistic Lock

**적용 도메인**: 선착순 쿠폰 사용

**특징**:
- 실패 시 재시도 가능
- 트래픽이 높아 Pessimistic Lock은 병목
- CAS (Compare-And-Swap) 방식

**구현 예시**:
```java
@Entity
public class Coupon {
    @Version
    private Long version;  // 낙관적 락을 위한 버전 컬럼
}

// 충돌 시 OptimisticLockingFailureException 발생
// → 재시도 로직 필요
```

#### ✔ 여러 서버가 동시에 경쟁할 때 → Distributed Lock (Redis)

**적용 도메인**: 선착순 쿠폰 발급 서버

**특징**:
- "쿠폰 5만 개 오픈" 같은 이벤트
- 여러 서버가 동시에 쿠폰 발급
- DB Lock은 병목 발생

**구현 예시**:
```java
String lockKey = "lock:coupon:issue:" + couponId;
Boolean lockAcquired = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10));
```

#### ✔ 읽기 트래픽이 압도적으로 많을 때 → 캐시 + Eventually Consistent

**적용 도메인**: 좋아요 수, 조회수, 인기 상품 집계

**특징**:
- 약간의 지연 허용 가능
- 읽기 성능 최우선
- 스케줄러로 주기적 동기화

**구현 예시**:
```java
// Insert-only로 쓰기 경합 없음
likeRepository.save(like);

// 스케줄러로 주기적 동기화
@Scheduled(fixedDelay = 5000)
public void syncLikeCounts() {
    // COUNT(*)로 실제 좋아요 수 계산 후 업데이트
}
```

---

### 7. 한눈에 보는 "정답 체크리스트"

| 질문 | 답 |
|------|-----|
| **트랜잭션만으로 동시성 문제 해결 가능?** | ❌ 못함 (트랜잭션은 원자성 보장이지 경쟁 제어가 아님) |
| **Isolation Level로 Lost Update 해결 가능?** | ❌ 못함 (락 필요) |
| **Pessimistic Lock은 언제?** | ⭐ 금융성, 데이터 무결성 최우선 |
| **Optimistic Lock은 언제?** | ⭐ 실패 허용 & 트래픽 높음 |
| **Redis Lock은 언제?** | ⭐ 멀티 서버 + 대규모 분산 시스템 |
| **Eventually Consistent는 언제?** | ⭐ 약한 정합성 OK, 읽기 많음 |

#### 상세 설명

**Q1: 트랜잭션만으로 동시성 문제 해결 가능?**
- ❌ **불가능**
- 트랜잭션은 **원자성(Atomicity)** 보장
- 하지만 **Lost Update**는 트랜잭션만으로는 해결 불가
- 락이 필요함

**Q2: Isolation Level로 Lost Update 해결 가능?**
- ❌ **불가능**
- Isolation Level은 **읽기 이상현상**만 해결
- Lost Update는 **쓰기 경합** 문제
- `SELECT ... FOR UPDATE` 같은 락이 필요

**Q3: Pessimistic Lock은 언제?**
- ⭐ **금융성, 데이터 무결성 최우선**
- 재고, 포인트, 주문 금액 등
- "기다려도 좋으니 절대 틀리면 안 됨"

**Q4: Optimistic Lock은 언제?**
- ⭐ **실패 허용 & 트래픽 높음**
- 선착순 쿠폰 사용
- 실패 시 재시도 가능한 경우

**Q5: Redis Lock은 언제?**
- ⭐ **멀티 서버 + 대규모 분산 시스템**
- 선착순 쿠폰 발급 (대규모 이벤트)
- 여러 서버가 동시에 경쟁하는 경우

**Q6: Eventually Consistent는 언제?**
- ⭐ **약한 정합성 OK, 읽기 많음**
- 좋아요 수, 조회수
- 약간의 지연 허용 가능한 경우

---

## 🎯 과제에서의 활용

### 1. 동시성 테스트 설계

**좋아요 동시성 테스트**:
```java
@Test
void concurrencyTest_likeCountShouldBeAccurate() {
    // 여러 사용자가 동시에 좋아요 추가
    // → 테이블 분리 방식: Insert-only로 경합 없음
    // → 컬럼 기반 방식: 락 경쟁 발생
}
```

**주문 동시성 테스트**:
```java
@Test
void concurrencyTest_orderShouldBeAtomic() {
    // 여러 사용자가 동시에 주문
    // → 비관적 락으로 정확성 보장
    // → 포인트 차감, 재고 차감 모두 정확하게 반영
}
```

**synchronized 문제 실험**:
```java
@Test
void synchronizedTest_transactionBoundaryIssue() {
    // synchronized 사용 시 Lost Update 발생
    // → 트랜잭션 경계와 락 범위 불일치 문제 보여줌
}
```

### 2. 설계 철학 설명

**좋아요 수**:
- "좋아요 수는 약간의 지연을 허용할 수 있는 값"
- "Eventually Consistent로 설계해도 됨"
- "성능과 확장성을 위해 하이브리드 방식 채택"
- "Insert-only로 쓰기 경합 없음 → 수평 확장 가능"

**주문/포인트**:
- "주문과 포인트는 돈과 직접 연결된 값"
- "강한 정합성이 필요함"
- "Strong Consistency를 위해 비관적 락 사용"
- "DB Record Lock으로 트랜잭션 경계와 락 범위 일치"

### 3. 락 전략 비교

**"왜 synchronized로 해결되면 안 되는가?"**:
- 트랜잭션 경계 바깥에서 락을 거는 바람에 동작이 깨짐
- 단일 인스턴스에서만 동작 (분산 환경 불가)
- 실험 코드로 Lost Update 발생 보여줌

**"분산 환경 락 처리 전략"**:
- synchronized: 분산 환경 불가
- Redis 분산 락: 복잡하지만 분산 환경 가능
- DB Record Lock: 분산 환경 자동 대응 (권장)

**"DB 락 전략 vs 애플리케이션 락 전략 비교"**:
- 애플리케이션 락 (synchronized, Redis): 트랜잭션 경계와 불일치
- DB 락 (SELECT ... FOR UPDATE): 트랜잭션 경계와 일치 (권장)

**"병목 분석"**:
- API 서버 Scale-Out 후 DB 병목 발생
- Hot Spot으로 인한 락 경쟁
- 테이블 분리 방식으로 해결

**"트래픽 급증 시 아키텍처 변경 방안"**:
- Insert-only로 쓰기 경합 제거
- 하이브리드 방식으로 조회 성능 향상
- Redis 캐시로 DB 부하 분산

---

## 📝 권장 사항

### 현재 프로젝트 개선 방안

1. **단기**: 현재 구조 유지 (테이블 분리)
   - 이미 잘 작동하고 있음
   - UNIQUE 제약조건으로 중복 방지
   - Insert-only로 쓰기 경합 없음

2. **중기**: 하이브리드 방식 도입
   - Product 테이블에 `like_count` 컬럼 추가
   - 스케줄러로 주기적 동기화 (5초 간격)
   - 조회 성능 향상

3. **장기**: Redis 캐시 추가
   - 대규모 트래픽 대응
   - 조회 성능 극대화

### 설계 선택 가이드

| 상황 | 권장 방식 |
|------|----------|
| **소규모 서비스** | 테이블 분리 (현재 방식) |
| **중규모 서비스** | 하이브리드 방식 |
| **대규모 서비스** | 하이브리드 + Redis 캐시 |
| **실시간 정확성 필수** | 컬럼 기반 + 비관적 락 |
| **성능 우선** | 하이브리드 + Redis 캐시 |

---

## 🔗 관련 문서

- [Lock 전략 설계](./09-lock-strategy.md)
- [트랜잭션 격리 수준 분석](./transaction-isolation-analysis.md)
- [ERD 설계](./04-erd.md)


