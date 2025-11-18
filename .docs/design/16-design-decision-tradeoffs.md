# 설계 결정 트레이드오프 및 가치관 충돌 시나리오

## 📌 개요

본 문서는 동시성 처리 설계에서 가치관이나 회사 상황에 따라 판단 내용이 달라질 수 있는 부분을 정리하고, 각 상황별 질문과 대안을 제시합니다.

---

## 🎯 가치관/상황에 따른 설계 결정 시나리오

### 시나리오 1: UNIQUE 인덱스 사용 여부

#### 상황

**DBA의 우려**:
- UNIQUE 인덱스는 성능 저하 이슈가 있음
- 인덱스 유지보수 비용 증가
- 대량 삽입 시 성능 저하

**개발자의 요구**:
- 데이터 무결성 보장 필요
- 애플리케이션 레벨 중복 체크는 동시성 문제 발생 가능

#### 질문 1: UNIQUE 인덱스 없이 중복 방지가 가능한가?

**현재 설계 (UNIQUE 인덱스 사용)**:
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
    @Column(name = "ref_user_id", nullable = false)
    private Long userId;
    
    @Column(name = "ref_product_id", nullable = false)
    private Long productId;
}

@Transactional
public void addLike(String userId, Long productId) {
    // 1. 중복 체크
    Optional<Like> existingLike = likeRepository.findByUserIdAndProductId(user.getId(), productId);
    if (existingLike.isPresent()) {
        return;
    }
    
    // 2. 저장 (UNIQUE 제약조건으로 최종 보호)
    Like like = Like.of(user.getId(), productId);
    try {
        likeRepository.save(like);
    } catch (DataIntegrityViolationException e) {
        // UNIQUE 제약조건 위반 예외 처리
        return;
    }
}
```

**대안 1: UNIQUE 인덱스 없이 Pessimistic Lock 사용**
```java
@Entity
@Table(name = "`like`")  // UNIQUE 인덱스 없음
public class Like {
    @Column(name = "ref_user_id", nullable = false)
    private Long userId;
    
    @Column(name = "ref_product_id", nullable = false)
    private Long productId;
}

@Repository
public interface LikeJpaRepository extends JpaRepository<Like, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Like l WHERE l.userId = :userId AND l.productId = :productId")
    Optional<Like> findByUserIdAndProductIdForUpdate(
        @Param("userId") Long userId, 
        @Param("productId") Long productId
    );
}

@Transactional
public void addLike(String userId, Long productId) {
    // 1. 비관적 락으로 중복 체크
    Optional<Like> existingLike = likeRepository.findByUserIdAndProductIdForUpdate(
        user.getId(), productId
    );
    if (existingLike.isPresent()) {
        return;
    }
    
    // 2. 저장 (락이 걸린 상태에서 저장)
    Like like = Like.of(user.getId(), productId);
    likeRepository.save(like);
}
```

**비교표**:

| 항목 | UNIQUE 인덱스 | Pessimistic Lock |
|------|-------------|-----------------|
| **성능** | ⚠️ 인덱스 유지보수 비용 | ✅ 인덱스 없음 |
| **동시성 처리** | ✅ DB 레벨 보호 | ⚠️ 락 경쟁 가능 |
| **데이터 무결성** | ✅ DB 레벨 보장 | ⚠️ 애플리케이션 레벨 보호 |
| **락 경쟁** | ✅ 없음 (Insert-only) | ❌ 있음 (같은 row 조회) |
| **DBA 선호도** | ❌ 인덱스 유지보수 부담 | ✅ 인덱스 없음 |

**판단 기준**:
- **DBA 우선**: Pessimistic Lock 사용 (인덱스 부담 감소)
- **데이터 무결성 우선**: UNIQUE 인덱스 사용 (DB 레벨 보장)
- **성능 우선**: UNIQUE 인덱스 사용 (Insert-only로 락 경쟁 없음)

**권장**: **UNIQUE 인덱스 사용** (Insert-only 패턴으로 락 경쟁 없음, DB 레벨 무결성 보장)

---

### 시나리오 2: Pessimistic Lock 사용 시 애플리케이션 장애 대응

#### 상황

**운영팀의 우려**:
- Pessimistic Lock 사용 시 애플리케이션이 죽으면 락이 유지됨
- 외부 개입 없이는 락이 해제되지 않음
- 타임아웃 설정이 필요함

**개발자의 요구**:
- 데이터 정합성 보장 필요
- Lost Update 방지 필수

#### 질문 2: Pessimistic Lock 대신 Optimistic Lock을 사용할 수 있는가?

**현재 설계 (Pessimistic Lock)**:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :productId")
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);

@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    Product product = productRepository.findByIdForUpdate(productId);
    product.decreaseStock(quantity);
    productRepository.save(product);
    // → 락이 트랜잭션 커밋까지 유지
    // → 애플리케이션 장애 시 락 유지 가능
}
```

**대안 1: Optimistic Lock 사용**
```java
@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private Integer stock;
    
    @Version  // 낙관적 락을 위한 버전 컬럼
    private Long version;
}

@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    int maxRetries = 3;
    int retryCount = 0;
    
    while (retryCount < maxRetries) {
        try {
            // 1. 일반 조회 (락 없음)
            Product product = productRepository.findById(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
            
            // 2. 재고 차감
            product.decreaseStock(quantity);
            
            // 3. 저장 (version 체크)
            productRepository.save(product);
            // → OptimisticLockingFailureException 발생 가능
            
            return OrderInfo.from(savedOrder);
            
        } catch (OptimisticLockingFailureException e) {
            retryCount++;
            if (retryCount >= maxRetries) {
                throw new CoreException(ErrorType.CONFLICT, 
                    "주문 처리 중 충돌이 발생했습니다. 다시 시도해주세요.");
            }
            // 재시도 전 짧은 대기
            try {
                Thread.sleep(10 + (retryCount * 10));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new CoreException(ErrorType.INTERNAL_ERROR, 
                    "주문 처리 중 중단되었습니다.");
            }
        }
    }
}
```

**대안 2: Pessimistic Lock + 타임아웃 설정**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({
    @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")  // 5초 타임아웃
})
@Query("SELECT p FROM Product p WHERE p.id = :productId")
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
```

**비교표**:

| 항목 | Pessimistic Lock | Optimistic Lock | Pessimistic + Timeout |
|------|-----------------|----------------|----------------------|
| **정합성 보장** | ✅ 높음 | ⚠️ 중간 (재시도 필요) | ✅ 높음 |
| **락 유지 위험** | ❌ 애플리케이션 장애 시 락 유지 | ✅ 락 없음 | ⚠️ 타임아웃 후 해제 |
| **성능** | ⚠️ 락 경쟁 | ✅ 락 경쟁 없음 | ⚠️ 락 경쟁 |
| **재시도 필요** | ❌ 불필요 | ✅ 필요 | ❌ 불필요 |
| **운영팀 선호도** | ❌ 락 유지 위험 | ✅ 락 없음 | ⚠️ 타임아웃 설정 필요 |

**판단 기준**:
- **운영팀 우선**: Optimistic Lock 사용 (락 유지 위험 없음)
- **정합성 우선**: Pessimistic Lock 사용 (정확성 보장)
- **절충안**: Pessimistic Lock + 타임아웃 설정
- **⚠️ Hot Spot 고려**: 인기 상품에 요청이 몰릴 경우 DB 병목 발생 가능

**권장**: **도메인별 선택 + Hot Spot 고려**
- 재고/포인트: 
  - 트래픽 낮음: Pessimistic Lock + 타임아웃 (정합성 최우선)
  - Hot Spot 발생: Optimistic Lock 또는 Queueing 고려
- 쿠폰 사용: Optimistic Lock (실패 허용 가능)

---

### 시나리오 3: 주문 번호 생성 방식

#### 상황

**비즈니스 요구사항**:
- 주문 번호를 `KES-25111901001` 형식으로 발행해야 함
- 날짜 기반 순차 번호 필요
- 동시 주문 시 중복 방지 필요

**기술적 고려사항**:
- DB 시퀀스 vs 애플리케이션 생성
- 동시성 처리 필요

#### 질문 3: 주문 번호를 어떻게 생성할 것인가?

**대안 1: DB 시퀀스 사용**
```java
@Entity
@Table(name = "order")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "order_number", unique = true, nullable = false)
    private String orderNumber;  // KES-25111901001
}

@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 주문 번호 생성 (DB 시퀀스 사용)
    String orderNumber = generateOrderNumber();  // DB 시퀀스 기반
    
    // 2. 주문 생성
    Order order = Order.of(user.getId(), orderItems, orderNumber);
    orderRepository.save(order);
    
    return OrderInfo.from(order);
}

private String generateOrderNumber() {
    // DB 시퀀스에서 다음 번호 조회
    Long sequenceNumber = orderSequenceRepository.getNextSequence();
    String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
    return String.format("KES-%s%05d", date, sequenceNumber);
}
```

**대안 2: 애플리케이션 레벨 생성 (Redis 분산 락)**
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. Redis 분산 락으로 주문 번호 생성
    String orderNumber = generateOrderNumberWithRedisLock();
    
    // 2. 주문 생성
    Order order = Order.of(user.getId(), orderItems, orderNumber);
    orderRepository.save(order);
    
    return OrderInfo.from(order);
}

private String generateOrderNumberWithRedisLock() {
    String lockKey = "lock:order:number:generation";
    String lockValue = UUID.randomUUID().toString();
    
    try {
        // Redis 분산 락 획득
        Boolean lockAcquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10));
        
        if (!lockAcquired) {
            throw new CoreException(ErrorType.CONFLICT, 
                "주문 번호 생성 중입니다. 잠시 후 다시 시도해주세요.");
        }
        
        // 현재 날짜의 마지막 주문 번호 조회
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String lastOrderNumber = orderRepository.findLastOrderNumberByDate(date);
        
        // 다음 번호 생성
        Long nextNumber = extractNumber(lastOrderNumber) + 1;
        return String.format("KES-%s%05d", date, nextNumber);
        
    } finally {
        // 락 해제
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
```

**대안 3: 애플리케이션 레벨 생성 (DB Pessimistic Lock)**
```java
@Repository
public interface OrderNumberRepository extends JpaRepository<OrderNumber, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderNumber o WHERE o.date = :date")
    Optional<OrderNumber> findByDateForUpdate(@Param("date") String date);
}

@Entity
@Table(name = "order_number")
public class OrderNumber {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "date", unique = true, nullable = false)
    private String date;  // 251119
    
    @Column(name = "last_number", nullable = false)
    private Long lastNumber;
}

@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 비관적 락으로 주문 번호 생성
    String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
    OrderNumber orderNumber = orderNumberRepository.findByDateForUpdate(date)
        .orElseGet(() -> {
            OrderNumber newOrderNumber = new OrderNumber(date, 0L);
            return orderNumberRepository.save(newOrderNumber);
        });
    
    // 2. 다음 번호 생성
    orderNumber.increment();
    orderNumberRepository.save(orderNumber);
    
    String orderNumberStr = String.format("KES-%s%05d", date, orderNumber.getLastNumber());
    
    // 3. 주문 생성
    Order order = Order.of(user.getId(), orderItems, orderNumberStr);
    orderRepository.save(order);
    
    return OrderInfo.from(order);
}
```

**비교표**:

| 항목 | DB 시퀀스 | Redis 분산 락 | DB Pessimistic Lock |
|------|----------|--------------|-------------------|
| **성능** | ✅ 빠름 | ⚠️ Redis 네트워크 지연 | ⚠️ 락 경쟁 |
| **동시성 처리** | ✅ DB 레벨 보장 | ✅ 분산 환경 대응 | ✅ DB 레벨 보장 |
| **락 유지 위험** | ✅ 없음 (DB 관리) | ⚠️ Redis 장애 시 문제 | ❌ 애플리케이션 장애 시 락 유지 |
| **인프라 의존성** | ✅ DB만 필요 | ❌ Redis 필요 | ✅ DB만 필요 |
| **운영 복잡도** | ✅ 낮음 | ⚠️ Redis 관리 필요 | ✅ 낮음 |

**판단 기준**:
- **인프라 단순성 우선**: DB 시퀀스 사용
- **분산 환경 대응**: Redis 분산 락 사용
- **운영팀 우선**: DB 시퀀스 사용 (락 유지 위험 없음)

**권장**: **DB 시퀀스 사용** (단순성, 성능, 운영 편의성)

---

### 시나리오 4: 트랜잭션 범위 (긴 트랜잭션 vs 짧은 트랜잭션)

#### 상황

**운영팀의 우려**:
- 긴 트랜잭션은 락 유지 시간 증가
- 데드락 위험 증가
- 타임아웃 발생 가능

**개발자의 요구**:
- 원자성 보장 필요
- All-or-Nothing 보장

#### 질문 4: 트랜잭션을 잘게 나눌 수 있는가?

**현재 설계 (하나의 트랜잭션)**:
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 사용자 조회 (락 획득)
    User user = loadUserForUpdate(userId);
    
    // 2. 상품 조회 (락 획득)
    List<Product> products = loadProducts(commands);
    
    // 3. 재고 차감
    decreaseStocks(products);
    
    // 4. 포인트 차감
    deductUserPoint(user);
    
    // 5. 주문 저장
    Order order = orderRepository.save(order);
    
    // → 하나의 트랜잭션으로 묶음
    // → 락 유지 시간이 길어질 수 있음
}
```

**대안 1: 트랜잭션 분리 (Saga 패턴)**
```java
// ❌ 권장하지 않음 (WAL 성능 저하)
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 트랜잭션 1: 재고 차감
    @Transactional
    void decreaseStocks(List<Product> products) {
        for (Product product : products) {
            Product p = productRepository.findByIdForUpdate(product.getId());
            p.decreaseStock(quantity);
            productRepository.save(p);
        }
        // → 커밋 I/O 1번
    }
    
    // 트랜잭션 2: 포인트 차감
    @Transactional
    void deductUserPoint(User user, Integer amount) {
        User u = userRepository.findByUserIdForUpdate(user.getUserId());
        u.deductPoint(Point.of(amount));
        userRepository.save(u);
        // → 커밋 I/O 1번
    }
    
    // 트랜잭션 3: 주문 저장
    @Transactional
    Order saveOrder(Order order) {
        return orderRepository.save(order);
        // → 커밋 I/O 1번
    }
    
    // → 총 커밋 I/O 3번 (성능 저하!)
}
```

**대안 2: 트랜잭션 유지 + 락 범위 최소화**
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 필요한 데이터만 빠르게 조회
    User user = loadUserForUpdate(userId);
    List<Product> products = loadProductsForUpdate(commands);
    
    // 2. 비즈니스 로직 실행 (락 유지)
    decreaseStocks(products);
    deductUserPoint(user);
    
    // 3. 주문 저장
    Order order = orderRepository.save(order);
    
    // → 하나의 트랜잭션 유지
    // → 락 범위 최소화로 락 유지 시간 단축
}
```

**비교표**:

| 항목 | 하나의 트랜잭션 | 트랜잭션 분리 | 트랜잭션 유지 + 락 최소화 |
|------|--------------|-------------|----------------------|
| **커밋 I/O** | ✅ 1번 | ❌ 여러 번 (성능 저하) | ✅ 1번 |
| **원자성 보장** | ✅ 보장 | ❌ 보장 안 됨 (부분 실패 가능) | ✅ 보장 |
| **락 유지 시간** | ⚠️ 길 수 있음 | ✅ 짧음 | ✅ 최소화 |
| **WAL 성능** | ✅ 최적 | ❌ 저하 | ✅ 최적 |
| **운영팀 선호도** | ⚠️ 락 유지 시간 우려 | ✅ 락 유지 시간 짧음 | ✅ 절충안 |

**판단 기준**:
- **WAL 성능 우선**: 하나의 트랜잭션 유지
- **락 유지 시간 우선**: 트랜잭션 분리 (하지만 원자성 문제)
- **절충안**: 트랜잭션 유지 + 락 범위 최소화

**권장**: **하나의 트랜잭션 유지 + 락 범위 최소화** (WAL 성능 + 원자성 보장)

---

### 시나리오 5: 읽기 전용 트랜잭션 사용 여부

#### 상황

**성능 팀의 우려**:
- 읽기 전용 트랜잭션이 실제로 성능 향상에 도움이 되는가?
- 오버헤드가 더 클 수 있음

**개발자의 요구**:
- 읽기 일관성 보장 필요
- 불필요한 락 방지

#### 질문 5: 읽기 전용 트랜잭션을 반드시 사용해야 하는가?

**현재 설계 (readOnly = true 사용)**:
```java
@Transactional(readOnly = true)
public List<OrderInfo> getOrders(String userId) {
    User user = loadUser(userId);
    List<Order> orders = orderRepository.findAllByUserId(user.getId());
    return orders.stream()
        .map(OrderInfo::from)
        .toList();
}
```

**대안 1: 트랜잭션 없이 조회**
```java
// 트랜잭션 없음
public List<OrderInfo> getOrders(String userId) {
    User user = loadUser(userId);
    List<Order> orders = orderRepository.findAllByUserId(user.getId());
    return orders.stream()
        .map(OrderInfo::from)
        .toList();
}
```

**대안 2: 일반 트랜잭션 사용**
```java
@Transactional  // readOnly = true 없음
public List<OrderInfo> getOrders(String userId) {
    User user = loadUser(userId);
    List<Order> orders = orderRepository.findAllByUserId(user.getId());
    return orders.stream()
        .map(OrderInfo::from)
        .toList();
}
```

**비교표**:

| 항목 | readOnly = true | 트랜잭션 없음 | 일반 트랜잭션 |
|------|----------------|-------------|-------------|
| **읽기 일관성** | ✅ 보장 | ❌ 보장 안 됨 | ✅ 보장 |
| **성능** | ✅ 최적 (읽기 최적화) | ✅ 빠름 (트랜잭션 오버헤드 없음) | ⚠️ 중간 |
| **락 설정** | ✅ S 락만 (최적화) | ✅ 락 없음 | ⚠️ 불필요한 락 가능 |
| **로그 I/O** | ✅ 최소화 | ✅ 없음 | ⚠️ 로그 기록 가능 |
| **성능 팀 선호도** | ⚠️ 오버헤드 우려 | ✅ 오버헤드 없음 | ❌ 불필요한 오버헤드 |

**판단 기준**:
- **읽기 일관성 우선**: `readOnly = true` 사용
- **성능 최우선**: 트랜잭션 없이 조회 (일관성 포기)
- **절충안**: `readOnly = true` 사용 (일관성 + 성능 최적화)

**권장**: **`readOnly = true` 사용** (읽기 일관성 + 성능 최적화)

---

### 시나리오 6: 인덱스 설계 (성능 vs 유지보수)

#### 상황

**DBA의 우려**:
- 인덱스가 많으면 INSERT/UPDATE 성능 저하
- 인덱스 유지보수 비용 증가
- 대량 삽입 시 성능 저하

**개발자의 요구**:
- 조회 성능 최적화 필요
- Lock 범위 최소화 필요

#### 질문 6: 인덱스를 최소화할 수 있는가?

**현재 설계 (인덱스 활용)**:
```java
@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;  // PK, 자동 인덱스
    
    // ...
}

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :productId")  // PK 인덱스 활용
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
```

**대안 1: 인덱스 없이 Full Scan (비권장)**
```java
@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "name")  // 인덱스 없음
    private String name;
}

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.name = :name")  // Full Scan 발생
Optional<Product> findByNameForUpdate(@Param("name") String name);
```

**대안 2: 애플리케이션 레벨 조회 후 락**
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 일반 조회 (인덱스 없이)
    Product product = productRepository.findByName(productName);
    
    // 2. ID로 다시 조회 (PK 인덱스 활용)
    Product lockedProduct = productRepository.findByIdForUpdate(product.getId());
    
    // 3. 재고 차감
    lockedProduct.decreaseStock(quantity);
    productRepository.save(lockedProduct);
}
```

**비교표**:

| 항목 | 인덱스 활용 | 인덱스 없음 | 애플리케이션 레벨 조회 |
|------|------------|-----------|-------------------|
| **조회 성능** | ✅ 빠름 | ❌ 느림 (Full Scan) | ⚠️ 2번 조회 |
| **Lock 범위** | ✅ 최소화 | ❌ 넓음 (경로상의 행) | ✅ 최소화 |
| **INSERT 성능** | ⚠️ 인덱스 유지 비용 | ✅ 빠름 | ⚠️ 인덱스 유지 비용 |
| **DBA 선호도** | ⚠️ 인덱스 유지보수 부담 | ✅ 인덱스 없음 | ⚠️ 인덱스 필요 |
| **동시성 성능** | ✅ 우수 | ❌ 저하 | ✅ 우수 |

**판단 기준**:
- **DBA 우선**: 인덱스 최소화 (하지만 Lock 범위 확대)
- **성능 우선**: 인덱스 활용 (조회 성능 + Lock 범위 최소화)
- **절충안**: 필수 인덱스만 사용 (PK, UNIQUE, 자주 조회하는 컬럼)

**권장**: **필수 인덱스만 사용** (PK, UNIQUE, 자주 조회하는 컬럼)

---

### 시나리오 7: 락 타임아웃 설정

#### 상황

**운영팀의 우려**:
- Pessimistic Lock 사용 시 애플리케이션 장애로 락이 유지됨
- 타임아웃 설정 필요

**개발자의 요구**:
- 정합성 보장 필요
- 타임아웃이 너무 짧으면 정상 요청도 실패 가능

#### 질문 7: 락 타임아웃을 어떻게 설정할 것인가?

**현재 설계 (타임아웃 없음)**:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :productId")
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
```

**대안 1: 짧은 타임아웃 (5초)**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({
    @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")  // 5초
})
@Query("SELECT p FROM Product p WHERE p.id = :productId")
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
```

**대안 2: 긴 타임아웃 (30초)**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({
    @QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000")  // 30초
})
@Query("SELECT p FROM Product p WHERE p.id = :productId")
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
```

**대안 3: 타임아웃 없음 (기본값 사용)**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :productId")
Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
// → DB 기본 타임아웃 사용 (보통 50초)
```

**비교표**:

| 항목 | 타임아웃 없음 | 짧은 타임아웃 (5초) | 긴 타임아웃 (30초) |
|------|-------------|------------------|------------------|
| **락 유지 위험** | ❌ 높음 (기본값 사용) | ✅ 낮음 | ⚠️ 중간 |
| **정상 요청 실패** | ✅ 없음 | ⚠️ 가능 (부하 시) | ✅ 거의 없음 |
| **운영팀 선호도** | ❌ 락 유지 위험 | ✅ 빠른 해제 | ⚠️ 중간 |
| **개발자 선호도** | ✅ 정상 요청 보호 | ⚠️ 부하 시 실패 가능 | ✅ 정상 요청 보호 |

**판단 기준**:
- **운영팀 우선**: 짧은 타임아웃 (락 유지 위험 감소)
- **정상 요청 보호**: 긴 타임아웃 또는 타임아웃 없음
- **절충안**: 중간 타임아웃 (10-15초)

**권장**: **중간 타임아웃 (10-15초)** (락 유지 위험 감소 + 정상 요청 보호)

---

## 📊 종합 비교표

### 가치관/상황별 설계 선택지

| 시나리오 | 현재 설계 | DBA 우선 | 운영팀 우선 | 성능 우선 | 정합성 우선 |
|---------|----------|---------|------------|----------|------------|
| **UNIQUE 인덱스** | ✅ 사용 | ❌ Pessimistic Lock | ✅ 사용 | ✅ 사용 | ✅ 사용 |
| **Pessimistic Lock** | ✅ 사용 | ⚠️ Optimistic Lock | ⚠️ Optimistic Lock | ⚠️ Optimistic Lock | ✅ 사용 |
| **주문 번호 생성** | - | ✅ DB 시퀀스 | ✅ DB 시퀀스 | ✅ DB 시퀀스 | ✅ DB 시퀀스 |
| **트랜잭션 범위** | ✅ 하나로 묶음 | ✅ 하나로 묶음 | ⚠️ 분리 고려 | ✅ 하나로 묶음 | ✅ 하나로 묶음 |
| **읽기 전용 트랜잭션** | ✅ 사용 | ✅ 사용 | ⚠️ 트랜잭션 없음 | ⚠️ 트랜잭션 없음 | ✅ 사용 |
| **인덱스 설계** | ✅ 필수만 사용 | ❌ 최소화 | ✅ 필수만 사용 | ✅ 필수만 사용 | ✅ 필수만 사용 |
| **락 타임아웃** | - | ⚠️ 중간 (10-15초) | ✅ 짧음 (5초) | ⚠️ 중간 (10-15초) | ⚠️ 긴 (30초) |

---

## 🎯 질문 체크리스트

### 설계 결정 시 고려할 질문들

#### 1. 인덱스 설계 관련

- [ ] **Q1-1**: UNIQUE 인덱스 없이 중복 방지가 가능한가?
  - 대안: Pessimistic Lock 사용
  - 트레이드오프: 인덱스 유지보수 vs 락 경쟁

- [ ] **Q1-2**: 인덱스를 최소화할 수 있는가?
  - 대안: 필수 인덱스만 사용 (PK, UNIQUE, 자주 조회하는 컬럼)
  - 트레이드오프: INSERT 성능 vs 조회 성능 + Lock 범위

#### 2. 락 전략 관련

- [ ] **Q2-1**: Pessimistic Lock 대신 Optimistic Lock을 사용할 수 있는가?
  - 대안: Optimistic Lock + 재시도 로직
  - 트레이드오프: 락 유지 위험 vs 정합성 보장

- [ ] **Q2-2**: 락 타임아웃을 어떻게 설정할 것인가?
  - 대안: 짧은 타임아웃 (5초) vs 긴 타임아웃 (30초) vs 중간 (10-15초)
  - 트레이드오프: 락 유지 위험 vs 정상 요청 실패 가능성

#### 3. 트랜잭션 범위 관련

- [ ] **Q3-1**: 트랜잭션을 잘게 나눌 수 있는가?
  - 대안: 트랜잭션 분리 vs 하나로 묶음
  - 트레이드오프: 락 유지 시간 vs 커밋 I/O 증가 + 원자성 문제

- [ ] **Q3-2**: 읽기 전용 트랜잭션을 반드시 사용해야 하는가?
  - 대안: `readOnly = true` vs 트랜잭션 없음
  - 트레이드오프: 읽기 일관성 vs 성능 오버헤드

#### 4. 주문 번호 생성 관련

- [ ] **Q4-1**: 주문 번호를 어떻게 생성할 것인가?
  - 대안: DB 시퀀스 vs Redis 분산 락 vs DB Pessimistic Lock
  - 트레이드오프: 인프라 단순성 vs 분산 환경 대응

#### 5. 성능 vs 정합성 관련

- [ ] **Q5-1**: 성능을 위해 정합성을 어느 정도 포기할 수 있는가?
  - 대안: Eventually Consistent vs Strong Consistency
  - 트레이드오프: 성능 vs 정확성

---

## 💡 권장 사항

### 현재 프로젝트에 대한 권장

1. **UNIQUE 인덱스 사용 유지** ✅
   - Insert-only 패턴으로 락 경쟁 없음
   - DB 레벨 무결성 보장

2. **Pessimistic Lock + 타임아웃 설정** ✅
   - 정합성 최우선 도메인 (재고, 포인트)
   - 타임아웃 10-15초 권장

3. **하나의 트랜잭션 유지** ✅
   - WAL 성능 최적화
   - 원자성 보장

4. **읽기 전용 트랜잭션 사용** ✅
   - 읽기 일관성 + 성능 최적화

5. **필수 인덱스만 사용** ✅
   - PK, UNIQUE, 자주 조회하는 컬럼

### 상황별 대안

**DBA가 인덱스 사용을 강하게 반대하는 경우**:
- Pessimistic Lock 사용 (인덱스 없이)
- 하지만 Lock 범위 확대로 동시성 성능 저하 가능

**운영팀이 락 유지 위험을 강하게 우려하는 경우**:
- Optimistic Lock 사용
- 하지만 재시도 로직 필요, 정합성 보장 약화

**비즈니스 요구사항으로 주문 번호 형식이 복잡한 경우**:
- DB 시퀀스 기반 생성 권장
- 또는 Redis 분산 락 (분산 환경 시)

---

## 🔗 관련 문서

- [동시성 처리 설계 원칙](./15-concurrency-design-principles.md)
- [락 설계 평가](./13-lock-design-evaluation.md)
- [WAL 성능 평가](./14-wal-performance-evaluation.md)

