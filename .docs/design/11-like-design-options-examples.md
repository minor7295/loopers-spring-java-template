# 좋아요 설계 옵션 구현 예시 코드

## 📌 개요

본 문서는 좋아요 설계 옵션별 실제 구현 예시 코드를 제공합니다.

---

## 옵션 1: 컬럼 기반 - 비관적 락 버전

### 엔티티

```java
package com.loopers.domain.product;

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
    
    public Long getLikeCount() {
        return likeCount;
    }
}
```

### Repository

```java
package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<Product, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :productId")
    Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
}
```

### Facade

```java
package com.loopers.application.like;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    // 중복 체크를 위한 별도 테이블 필요
    private final LikeRepository likeRepository;
    
    /**
     * 상품에 좋아요를 추가합니다. (비관적 락 버전)
     * 
     * 문제점:
     * - 하나의 상품 row에 쓰기 경합이 몰림
     * - 락 경쟁으로 인한 대기 시간 증가
     */
    @Transactional
    public void addLike(String userId, Long productId) {
        User user = loadUser(userId);
        
        // 비관적 락으로 상품 조회 (SELECT ... FOR UPDATE)
        Product product = productRepository.findByIdForUpdate(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, 
                "상품을 찾을 수 없습니다."));
        
        // 중복 체크 (별도 테이블 필요)
        if (likeRepository.existsByUserIdAndProductId(user.getId(), productId)) {
            return; // 이미 좋아요 함
        }
        
        // 좋아요 수 증가
        product.incrementLikeCount();
        productRepository.save(product);
        
        // 좋아요 기록 저장 (중복 체크용)
        Like like = Like.of(user.getId(), productId);
        likeRepository.save(like);
    }
    
    /**
     * 상품의 좋아요 수를 조회합니다.
     */
    public Long getLikeCount(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, 
                "상품을 찾을 수 없습니다."));
        return product.getLikeCount();
    }
    
    private User loadUser(String userId) {
        User user = userRepository.findByUserId(userId);
        if (user == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return user;
    }
}
```

### 동시성 테스트

```java
@Test
void concurrencyTest_likeCountWithPessimisticLock() throws InterruptedException {
    // arrange
    Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());
    Long productId = product.getId();
    
    int userCount = 100;
    List<User> users = new ArrayList<>();
    for (int i = 0; i < userCount; i++) {
        users.add(createAndSaveUser("user" + i, "user" + i + "@example.com", 0L));
    }
    
    ExecutorService executorService = Executors.newFixedThreadPool(userCount);
    CountDownLatch latch = new CountDownLatch(userCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();
    
    // act
    for (User user : users) {
        executorService.submit(() -> {
            try {
                likeFacade.addLike(user.getUserId(), productId);
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
    
    // 문제: 락 경쟁으로 인해 일부 요청이 대기하거나 실패할 수 있음
    assertThat(savedProduct.getLikeCount()).isEqualTo(userCount);
    assertThat(successCount.get()).isEqualTo(userCount);
}
```

**문제점**:
- ❌ 락 경쟁: 100명이 동시에 같은 상품에 좋아요 → 99명이 대기
- ❌ 성능 저하: 대기 시간 증가
- ❌ 확장성 낮음: 트래픽 증가 시 병목 발생

---

## 옵션 2: 컬럼 기반 - 낙관적 락 버전

### 엔티티

```java
package com.loopers.domain.product;

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
    
    public Long getLikeCount() {
        return likeCount;
    }
}
```

### Facade

```java
package com.loopers.application.like;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final LikeRepository likeRepository;
    
    private static final int MAX_RETRIES = 3;
    
    /**
     * 상품에 좋아요를 추가합니다. (낙관적 락 버전)
     * 
     * 장점:
     * - 비관적 락보다 락 경쟁 적음
     * - 재시도 로직으로 충돌 처리
     * 
     * 단점:
     * - 재시도 로직 필요
     * - 일부 실패 가능
     */
    @Transactional
    public void addLike(String userId, Long productId) {
        User user = loadUser(userId);
        int retryCount = 0;
        
        while (retryCount < MAX_RETRIES) {
            try {
                // 낙관적 락으로 상품 조회
                Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, 
                        "상품을 찾을 수 없습니다."));
                
                // 중복 체크
                if (likeRepository.existsByUserIdAndProductId(user.getId(), productId)) {
                    return;
                }
                
                // 좋아요 수 증가 (CAS: Compare-And-Swap)
                product.incrementLikeCount();
                productRepository.save(product);  // version 체크 후 업데이트
                
                // 좋아요 기록 저장
                Like like = Like.of(user.getId(), productId);
                likeRepository.save(like);
                
                return; // 성공
                
            } catch (OptimisticLockingFailureException e) {
                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    throw new CoreException(ErrorType.CONFLICT, 
                        "좋아요 처리 중 충돌이 발생했습니다. 다시 시도해주세요.");
                }
                // 재시도 전 짧은 대기 (Exponential Backoff)
                try {
                    Thread.sleep(10 + (retryCount * 10)); // 10ms, 20ms, 30ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CoreException(ErrorType.INTERNAL_ERROR, 
                        "좋아요 처리 중 중단되었습니다.");
                }
            }
        }
    }
}
```

### 동시성 테스트

```java
@Test
void concurrencyTest_likeCountWithOptimisticLock() throws InterruptedException {
    // arrange
    Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());
    Long productId = product.getId();
    
    int userCount = 100;
    List<User> users = new ArrayList<>();
    for (int i = 0; i < userCount; i++) {
        users.add(createAndSaveUser("user" + i, "user" + i + "@example.com", 0L));
    }
    
    ExecutorService executorService = Executors.newFixedThreadPool(userCount);
    CountDownLatch latch = new CountDownLatch(userCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger retryCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();
    
    // act
    for (User user : users) {
        executorService.submit(() -> {
            try {
                likeFacade.addLike(user.getUserId(), productId);
                successCount.incrementAndGet();
            } catch (CoreException e) {
                if (e.getErrorType() == ErrorType.CONFLICT) {
                    retryCount.incrementAndGet();
                }
                synchronized (exceptions) {
                    exceptions.add(e);
                }
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
    
    // 일부는 재시도 후 성공, 일부는 실패할 수 있음
    assertThat(savedProduct.getLikeCount()).isLessThanOrEqualTo(userCount);
    // 재시도가 발생했을 수 있음
    assertThat(retryCount.get()).isGreaterThanOrEqualTo(0);
}
```

**장점**:
- ✅ 락 경쟁 감소: 비관적 락보다 대기 시간 적음
- ✅ 재시도 로직으로 충돌 처리

**단점**:
- ❌ 재시도 로직 필요
- ❌ 일부 실패 가능 (재시도 횟수 초과 시)

---

## 옵션 3: 하이브리드 방식 (Eventually Consistent)

### 엔티티

```java
package com.loopers.domain.product;

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
    private Long likeCount = 0L;  // 캐시된 좋아요 수
}

// Like 테이블은 그대로 유지
@Entity
@Table(name = "`like`")
public class Like {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "ref_user_id", nullable = false)
    private Long userId;
    
    @Column(name = "ref_product_id", nullable = false)
    private Long productId;
}
```

### Facade

```java
package com.loopers.application.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    
    /**
     * 상품에 좋아요를 추가합니다. (하이브리드 방식)
     * 
     * 장점:
     * - Insert-only로 쓰기 경합 없음
     * - 중복 체크 가능 (UNIQUE 제약조건)
     * - like_count는 스케줄러가 주기적으로 동기화
     */
    @Transactional
    public void addLike(String userId, Long productId) {
        User user = loadUser(userId);
        loadProduct(productId);
        
        // Like 테이블에 Insert-only (쓰기 경합 없음)
        Like like = Like.of(user.getId(), productId);
        try {
            likeRepository.save(like);
        } catch (DataIntegrityViolationException e) {
            // 이미 좋아요 함 (멱등성 보장)
            return;
        }
        
        // like_count는 스케줄러가 주기적으로 동기화
        // 여기서는 즉시 업데이트하지 않음 (Eventually Consistent)
    }
    
    /**
     * 상품의 좋아요 수를 조회합니다.
     * 
     * 주의: 약간의 지연이 있을 수 있음 (스케줄러 동기화 주기에 따라)
     */
    public Long getLikeCount(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, 
                "상품을 찾을 수 없습니다."));
        return product.getLikeCount();  // 캐시된 값
    }
}
```

### 스케줄러

```java
package com.loopers.application.like;

import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class LikeCountSyncScheduler {
    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;
    
    /**
     * 5초마다 좋아요 수를 동기화합니다.
     * 
     * 최근 업데이트된 상품들만 동기화하여 성능 최적화
     */
    @Scheduled(fixedDelay = 5000) // 5초마다
    public void syncLikeCounts() {
        // 최근 좋아요가 추가/삭제된 상품 ID 목록 조회
        List<Long> recentlyUpdatedProductIds = getRecentlyUpdatedProductIds();
        
        for (Long productId : recentlyUpdatedProductIds) {
            // COUNT(*)로 실제 좋아요 수 계산
            Long actualCount = likeRepository.countByProductId(productId);
            
            // Product 테이블의 like_count 업데이트
            productRepository.updateLikeCount(productId, actualCount);
        }
    }
    
    private List<Long> getRecentlyUpdatedProductIds() {
        // 최근 5초 이내에 좋아요가 추가/삭제된 상품 ID 목록 조회
        // 구현 생략 (예: created_at 또는 updated_at 기준)
        return likeRepository.findRecentlyUpdatedProductIds();
    }
}
```

### 동시성 테스트

```java
@Test
void concurrencyTest_likeCountWithHybrid() throws InterruptedException {
    // arrange
    Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());
    Long productId = product.getId();
    
    int userCount = 100;
    List<User> users = new ArrayList<>();
    for (int i = 0; i < userCount; i++) {
        users.add(createAndSaveUser("user" + i, "user" + i + "@example.com", 0L));
    }
    
    ExecutorService executorService = Executors.newFixedThreadPool(userCount);
    CountDownLatch latch = new CountDownLatch(userCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();
    
    // act
    for (User user : users) {
        executorService.submit(() -> {
            try {
                likeFacade.addLike(user.getUserId(), productId);
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
    
    // 스케줄러가 동기화할 때까지 대기
    Thread.sleep(6000); // 6초 대기 (스케줄러 주기 5초)
    
    // assert
    Product savedProduct = productRepository.findById(productId).orElseThrow();
    
    // Insert-only로 경합 없음 → 모든 요청 성공
    assertThat(successCount.get()).isEqualTo(userCount);
    assertThat(exceptions).isEmpty();
    
    // 스케줄러가 동기화한 후 좋아요 수 확인
    assertThat(savedProduct.getLikeCount()).isEqualTo(userCount);
}
```

**장점**:
- ✅ 쓰기 경합 없음: Insert-only
- ✅ 조회 성능 우수: 컬럼만 읽으면 됨
- ✅ 중복 체크 가능: UNIQUE 제약조건
- ✅ 확장성: 대규모 트래픽 처리 가능

**단점**:
- ⚠️ 약간의 지연: 스케줄러 동기화 주기에 따라 5초 정도 지연 가능

---

## 비교 요약

| 방식 | 쓰기 경합 | 조회 성능 | 중복 체크 | 정확성 | 구현 복잡도 |
|------|----------|----------|----------|--------|------------|
| **비관적 락** | ❌ 심함 | ✅ 빠름 | ❌ 별도 테이블 | ✅ 즉시 | ⭐⭐ |
| **낙관적 락** | ⚠️ 중간 | ✅ 빠름 | ❌ 별도 테이블 | ✅ 즉시 | ⭐⭐⭐ |
| **하이브리드** | ✅ 없음 | ✅ 빠름 | ✅ 가능 | ⚠️ 약간 지연 | ⭐⭐⭐⭐ |

**권장**: 하이브리드 방식 (Eventually Consistent)


