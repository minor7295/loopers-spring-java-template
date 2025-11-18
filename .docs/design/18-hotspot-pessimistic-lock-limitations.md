# Hot Spot 문제와 비관적 락의 한계

## 📌 개요

본 문서는 인기 상품에 요청이 몰릴 때 발생하는 Hot Spot 문제와 비관적 락의 한계를 설명하고, 대안 전략을 제시합니다.

---

## 🎯 핵심 문제: Hot Spot에서의 비관적 락 한계

### 문제 상황

**인기 상품에 재고 차감 요청이 몰릴 때**:
- 100명이 동시에 같은 상품을 주문
- 모두 같은 Product row를 업데이트하려고 함
- 비관적 락으로 인해 순차 처리됨
- **API 서버를 늘려도 DB 락 경쟁으로 처리량 증가 제한**

---

## 📊 Hot Spot 문제 분석

### 1. 비관적 락 사용 시 Hot Spot 문제

#### 현재 설계 (비관적 락)

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 인기 상품에 100명이 동시에 주문
    Product product = productRepository.findByIdForUpdate(productId);
    // ↑ SELECT ... FOR UPDATE → 락 획득
    
    product.decreaseStock(quantity);
    productRepository.save(product);
    // ↑ UPDATE product SET stock = ... WHERE id = ?
}
```

#### 타임라인 (100명 동시 주문)

```
T1: SELECT ... FOR UPDATE (락 획득) → stock = 100 → UPDATE → 커밋 (락 해제)
T2: 대기... (T1이 락을 해제할 때까지)
T3: 대기... (T1이 락을 해제할 때까지)
T4: 대기... (T1이 락을 해제할 때까지)
...
T100: 대기... (T1이 락을 해제할 때까지)

→ T1이 끝나면 T2가 락 획득 → 처리 → 락 해제
→ T2가 끝나면 T3가 락 획득 → 처리 → 락 해제
...
```

#### 결과

- ❌ **순차 처리**: 100명이 순차적으로 처리됨 (병렬 처리 불가)
- ❌ **대기 시간 증가**: 평균 대기 시간 = (처리 시간 × 대기 순서)
- ❌ **처리량 감소**: 초당 처리 가능한 요청 수가 급격히 감소
- ❌ **DB 병목**: API 서버를 늘려도 DB 락 경쟁으로 처리량 증가 제한
- ❌ **타임아웃 발생**: 대기 시간이 너무 길어지면 타임아웃 발생

#### API 서버 Scale-Out의 한계

```
API 서버 1대: 10 req/s (비관적 락으로 순차 처리)
API 서버 10대: 10 req/s (여전히 DB 락 경쟁으로 순차 처리)
API 서버 100대: 10 req/s (여전히 DB 락 경쟁으로 순차 처리)

→ API 서버를 늘려도 DB 락 경쟁으로 처리량 증가 제한!
```

**핵심 문제**: **DB는 단일 서버이므로 락 경쟁이 발생하면 순차 처리됨**

---

### 2. 좋아요 기능의 Hot Spot 해결 (참고)

#### 현재 설계 (Insert-only 패턴)

```java
@Transactional
public void addLike(String userId, Long productId) {
    // 100명이 동시에 좋아요 추가
    Like like = Like.of(user.getId(), productId);
    likeRepository.save(like);
    // ↑ INSERT INTO like (user_id, product_id) VALUES (?, ?)
    // → 각각 다른 row에 삽입 → 락 경쟁 없음!
}
```

#### 타임라인 (100명 동시 좋아요)

```
T1: INSERT INTO like (user_id=1, product_id=100) → 성공
T2: INSERT INTO like (user_id=2, product_id=100) → 성공 (다른 row)
T3: INSERT INTO like (user_id=3, product_id=100) → 성공 (다른 row)
...
T100: INSERT INTO like (user_id=100, product_id=100) → 성공 (다른 row)

→ 모두 동시에 처리 가능! (병렬 처리)
```

#### 결과

- ✅ **병렬 처리**: 100명이 동시에 처리됨
- ✅ **대기 시간 없음**: 락 경쟁 없음
- ✅ **처리량 증가**: 초당 처리 가능한 요청 수가 크게 증가
- ✅ **확장성**: API 서버 증가에 비례하여 처리량 증가

**차이**: **100배 성능 차이!**

---

## 🎯 재고 차감에서 Hot Spot 문제 해결 방안

### 방안 1: Optimistic Lock 사용 (트래픽 높을 때)

#### 적용 시나리오

- **트래픽이 높고 Hot Spot 발생**
- **실패 허용 가능** (재시도 로직 필요)
- **정확성보다 성능 우선**

#### 구현 예시

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

#### 장단점

| 항목 | 평가 | 설명 |
|------|------|------|
| **Hot Spot 대응** | ✅ 우수 | 락 경쟁 없음, 병렬 처리 가능 |
| **처리량** | ✅ 높음 | API 서버 증가에 비례하여 처리량 증가 |
| **정확성** | ⚠️ 중간 | 재시도 필요, 일부 실패 가능 |
| **복잡도** | ⚠️ 중간 | 재시도 로직 필요 |

---

### 방안 2: Queueing (메시지 큐 사용)

#### 적용 시나리오

- **트래픽이 매우 높고 Hot Spot 발생**
- **정확성 최우선이지만 성능도 중요**
- **약간의 지연 허용 가능** (비동기 처리)

#### 구현 예시

```java
@Service
public class OrderService {
    private final RabbitTemplate rabbitTemplate;
    private final OrderRepository orderRepository;
    
    // 1. 주문 요청을 큐에 넣기
    public void createOrderAsync(String userId, List<OrderItemCommand> commands) {
        OrderRequest request = new OrderRequest(userId, commands);
        rabbitTemplate.convertAndSend("order.queue", request);
    }
    
    // 2. 큐에서 주문 처리 (순차 처리)
    @RabbitListener(queues = "order.queue")
    @Transactional
    public void processOrder(OrderRequest request) {
        // 비관적 락으로 정확성 보장
        Product product = productRepository.findByIdForUpdate(request.getProductId());
        product.decreaseStock(request.getQuantity());
        productRepository.save(product);
        
        // 주문 저장
        Order order = Order.of(request.getUserId(), request.getItems());
        orderRepository.save(order);
    }
}
```

#### 장단점

| 항목 | 평가 | 설명 |
|------|------|------|
| **Hot Spot 대응** | ✅ 우수 | 큐로 요청 분산, 순차 처리 |
| **처리량** | ✅ 높음 | 큐 처리량에 따라 결정 |
| **정확성** | ✅ 높음 | 비관적 락으로 정확성 보장 |
| **지연** | ⚠️ 있음 | 비동기 처리로 약간의 지연 |
| **복잡도** | ❌ 높음 | 메시지 큐 인프라 필요 |

---

### 방안 3: 배치 처리 (대량 주문 시)

#### 적용 시나리오

- **대량 주문이 예상되는 경우**
- **정확성 최우선**
- **약간의 지연 허용 가능**

#### 구현 예시

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 상품별로 그룹화
    Map<Long, Integer> productQuantityMap = commands.stream()
        .collect(Collectors.groupingBy(
            OrderItemCommand::productId,
            Collectors.summingInt(OrderItemCommand::quantity)
        ));
    
    // 2. 배치로 재고 차감
    for (Map.Entry<Long, Integer> entry : productQuantityMap.entrySet()) {
        Product product = productRepository.findByIdForUpdate(entry.getKey());
        product.decreaseStock(entry.getValue());
        productRepository.save(product);
    }
    
    // 3. 주문 저장
    Order order = Order.of(user.getId(), orderItems);
    orderRepository.save(order);
    
    return OrderInfo.from(order);
}
```

#### 장단점

| 항목 | 평가 | 설명 |
|------|------|------|
| **Hot Spot 대응** | ⚠️ 중간 | 락 획득 횟수 감소 |
| **처리량** | ⚠️ 중간 | 락 획득 횟수 감소로 성능 향상 |
| **정확성** | ✅ 높음 | 비관적 락으로 정확성 보장 |
| **복잡도** | ✅ 낮음 | 구현 간단 |

---

### 방안 4: 하이브리드 방식 (트래픽에 따라 전략 선택)

#### 적용 시나리오

- **트래픽이 낮을 때**: Pessimistic Lock (정확성 우선)
- **트래픽이 높을 때**: Optimistic Lock 또는 Queueing (성능 우선)

#### 구현 예시

```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 트래픽에 따라 전략 선택
    if (isHighTrafficProduct(productId)) {
        // 높은 트래픽: Optimistic Lock
        return createOrderWithOptimisticLock(userId, commands);
    } else {
        // 낮은 트래픽: Pessimistic Lock
        return createOrderWithPessimisticLock(userId, commands);
    }
}

private boolean isHighTrafficProduct(Long productId) {
    // 상품별 트래픽 모니터링
    // 예: 최근 1분간 주문 수가 100개 이상이면 높은 트래픽
    return orderCountService.getRecentOrderCount(productId, Duration.ofMinutes(1)) > 100;
}
```

#### 장단점

| 항목 | 평가 | 설명 |
|------|------|------|
| **Hot Spot 대응** | ✅ 우수 | 트래픽에 따라 적절한 전략 선택 |
| **처리량** | ✅ 높음 | 상황에 맞는 최적 전략 |
| **정확성** | ✅ 높음 | 낮은 트래픽에서는 정확성 보장 |
| **복잡도** | ❌ 높음 | 트래픽 모니터링 및 전략 선택 로직 필요 |

---

## 📊 비교표: Hot Spot 대응 전략

| 전략 | Hot Spot 대응 | 처리량 | 정확성 | 복잡도 | 적용 시나리오 |
|------|-------------|--------|--------|--------|-------------|
| **Pessimistic Lock** | ❌ 낮음 | ❌ 낮음 | ✅ 높음 | ✅ 낮음 | 트래픽 낮음, 정확성 최우선 |
| **Optimistic Lock** | ✅ 높음 | ✅ 높음 | ⚠️ 중간 | ⚠️ 중간 | 트래픽 높음, 실패 허용 가능 |
| **Queueing** | ✅ 높음 | ✅ 높음 | ✅ 높음 | ❌ 높음 | 트래픽 매우 높음, 약간의 지연 허용 |
| **배치 처리** | ⚠️ 중간 | ⚠️ 중간 | ✅ 높음 | ✅ 낮음 | 대량 주문 예상 |
| **하이브리드** | ✅ 높음 | ✅ 높음 | ✅ 높음 | ❌ 높음 | 트래픽 변동이 큰 경우 |

---

## 🎯 판단 기준

### Hot Spot 발생 시 비관적 락 사용 여부

#### ✅ 비관적 락 사용 (권장)

**조건**:
- 트래픽이 낮거나 중간 (초당 100건 이하)
- 정확성이 최우선
- Hot Spot 발생 빈도가 낮음

**예시**:
- 일반 상품 주문
- 포인트 차감 (사용자별로 분산)

#### ⚠️ 비관적 락 + 대안 고려

**조건**:
- 트래픽이 높거나 Hot Spot 발생 (초당 100건 이상)
- 정확성이 중요하지만 성능도 고려 필요
- API 서버 Scale-Out 후에도 DB 병목 발생

**대안**:
- Optimistic Lock (실패 허용 가능)
- Queueing (약간의 지연 허용 가능)
- 배치 처리 (대량 주문 예상)

#### ❌ 비관적 락 부적합

**조건**:
- 트래픽이 매우 높음 (초당 1,000건 이상)
- Hot Spot이 지속적으로 발생
- API 서버를 늘려도 처리량 증가 제한

**대안**:
- Queueing 필수
- 하이브리드 방식 고려

---

## 📋 현재 프로젝트 평가

### 재고 차감 (현재: Pessimistic Lock)

#### ✅ 적절한 경우

- 트래픽이 낮거나 중간
- 일반 상품 주문
- 정확성 최우선

#### ⚠️ 개선 필요할 수 있는 경우

- 인기 상품에 주문이 몰릴 때
- API 서버를 늘려도 처리량 증가 제한
- DB 병목 발생

#### 권장 개선 방안

1. **단기**: 현재 구조 유지 (트래픽이 낮은 경우)
2. **중기**: Hot Spot 모니터링 추가
3. **장기**: 트래픽이 높아지면 Optimistic Lock 또는 Queueing 고려

---

## 💡 실무 권장 사항

### 1. Hot Spot 모니터링

- 상품별 주문 수 모니터링
- DB 락 대기 시간 모니터링
- API 서버 증가 대비 처리량 증가율 확인

### 2. 단계적 개선

1. **1단계**: 현재 구조 유지 + 모니터링
2. **2단계**: Hot Spot 발생 시 Optimistic Lock 도입
3. **3단계**: 트래픽이 매우 높아지면 Queueing 도입

### 3. 도메인별 전략 선택

| 도메인 | 현재 전략 | Hot Spot 발생 시 대안 |
|--------|----------|---------------------|
| **재고 차감** | Pessimistic Lock | Optimistic Lock 또는 Queueing |
| **포인트 차감** | Pessimistic Lock | Optimistic Lock (사용자별로 분산되어 Hot Spot 적음) |
| **좋아요** | Insert-only | 현재 방식 유지 (Hot Spot 없음) |

---

## 🔗 관련 문서

- [동시성 처리 설계 원칙](./15-concurrency-design-principles.md)
- [읽기/쓰기 트레이드오프](./12-read-write-tradeoff-reason.md)
- [설계 결정 트레이드오프](./16-design-decision-tradeoffs.md)
- [좋아요 설계 옵션 비교](./11-like-design-options.md)

