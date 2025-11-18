# WAL 성능 평가 및 개선 방안

## 📌 개요

본 문서는 Write-Ahead Logging(WAL)과 로그 I/O 병목 관점에서 현재 프로젝트의 구조를 평가하고 개선 방안을 제시합니다.

---

## 🔍 WAL 기본 개념

### 1. Write-Ahead Logging (WAL)

**로그 레코드는 커밋 전에 반드시 디스크에 "하드닝(쓰기)" 되어야 함**

**이유**:
- 장애가 나도 트랜잭션을 재현할 수 있어야 함
- 커밋은 반드시 **동기 I/O**를 필요로 함

**성능 영향**:
- **트랜잭션을 남발하면 성능이 떨어지는 이유**: 각 커밋마다 동기 I/O 발생
- **"트랜잭션을 잘게 나누면 더 빠르지 않을까?"** → ❌ 오히려 commit I/O 증가로 더 느려짐

### 2. WRITELOG / LOGBUFFER 대기

**로그 I/O가 느리면**:
- 모든 트랜잭션이 commit 시점에서 기다림
- 동시 요청이 증가할수록 로그 I/O가 병목
- 결국 전체 처리량 ↓

**과제 적용**:
- 동시성 실험에서 TPS(Throughput)가 갑자기 떨어지는 원인 설명
- "부하 테스트 분석" 문서에 활용 가능

### 3. 자동 커밋(autocommit)이 성능을 저하시키는 이유

**자동 커밋에서는 각각의 UPDATE/INSERT가 BEGIN TRAN + COMMIT을 동반**:
- 로그 레코드 증가
- 로그 I/O 증가
- 성능 저하

**과제 적용**:
- 스프링에서 트랜잭션을 적절히 묶어야 하는 이유 설명
- "트랜잭션 범위를 서비스 레이어에서 일관되게 관리해야 하는 이유" 근거

### 4. 대량 수정이 로그 크기와 I/O 비용을 증가시킴

**대량 트래픽 상황에서**:
- 읽기보다 쓰기 트랜잭션이 더 위험한 이유
- Redis 캐시로 읽기 부하를 이관해야 하는 아키텍처 논리 강화

---

## 📊 현재 프로젝트 구조 평가

### 1. 트랜잭션 범위 분석

#### ✅ 잘 설계된 부분

**1. PurchasingFacade.createOrder**
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 1. 사용자 조회 (포인트 차감용)
    User user = loadUserForUpdate(userId);
    
    // 2. 상품 조회 (재고 차감용)
    for (OrderItemCommand command : commands) {
        Product product = productRepository.findByIdForUpdate(command.productId());
        // ...
    }
    
    // 3. 재고 차감
    decreaseStocksForOrderItems(order.getItems(), products);
    
    // 4. 포인트 차감
    deductUserPoint(user, order.getTotalAmount());
    
    // 5. 주문 저장
    Order savedOrder = orderRepository.save(order);
    
    return OrderInfo.from(savedOrder);
    // → 트랜잭션 커밋 (1번의 동기 I/O)
}
```

**평가**:
- ✅ **하나의 유즈케이스 단위로 트랜잭션 묶음**: 적절함
- ✅ **1번의 커밋으로 모든 작업 처리**: 로그 I/O 최소화
- ✅ **원자성 보장**: 주문 생성 실패 시 모든 작업 롤백

**2. LikeFacade.addLike**
```java
@Transactional
public void addLike(String userId, Long productId) {
    // 1. 사용자 조회
    User user = loadUser(userId);
    
    // 2. 상품 조회
    loadProduct(productId);
    
    // 3. 중복 체크
    Optional<Like> existingLike = likeRepository.findByUserIdAndProductId(user.getId(), productId);
    if (existingLike.isPresent()) {
        return;
    }
    
    // 4. 좋아요 저장
    Like like = Like.of(user.getId(), productId);
    likeRepository.save(like);
    // → 트랜잭션 커밋 (1번의 동기 I/O)
}
```

**평가**:
- ✅ **하나의 유즈케이스 단위로 트랜잭션 묶음**: 적절함
- ✅ **1번의 커밋으로 모든 작업 처리**: 로그 I/O 최소화

#### ⚠️ 개선 가능한 부분

**1. PurchasingFacade.getOrders (읽기 전용 트랜잭션 미사용)**

```java
@Transactional  // ⚠️ 읽기 전용인데 readOnly = true 없음
public List<OrderInfo> getOrders(String userId) {
    User user = loadUser(userId);
    List<Order> orders = orderRepository.findAllByUserId(user.getId());
    return orders.stream()
        .map(OrderInfo::from)
        .toList();
}
```

**문제점**:
- 읽기 전용인데 `readOnly = true` 없음
- 불필요한 쓰기 락 설정 가능성
- 로그 버퍼에 불필요한 정보 기록 가능

**개선 방안**:
```java
@Transactional(readOnly = true)  // ✅ 읽기 전용 명시
public List<OrderInfo> getOrders(String userId) {
    // ...
}
```

**2. PurchasingFacade.getOrder (읽기 전용 트랜잭션 미사용)**

```java
@Transactional  // ⚠️ 읽기 전용인데 readOnly = true 없음
public OrderInfo getOrder(String userId, Long orderId) {
    User user = loadUser(userId);
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    return OrderInfo.from(order);
}
```

**개선 방안**:
```java
@Transactional(readOnly = true)  // ✅ 읽기 전용 명시
public OrderInfo getOrder(String userId, Long orderId) {
    // ...
}
```

**3. LikeFacade.getLikedProducts (트랜잭션 없음)**

```java
// ⚠️ 트랜잭션 없음
public List<LikedProduct> getLikedProducts(String userId) {
    User user = loadUser(userId);
    List<Like> likes = likeRepository.findAllByUserId(user.getId());
    // ...
}
```

**문제점**:
- 트랜잭션이 없어 일관성 보장 안 됨
- 하지만 읽기 전용이므로 로그 I/O는 없음

**개선 방안**:
```java
@Transactional(readOnly = true)  // ✅ 읽기 일관성 보장
public List<LikedProduct> getLikedProducts(String userId) {
    // ...
}
```

---

### 2. 트랜잭션 크기 분석

#### 현재 트랜잭션 크기

| 메서드 | 트랜잭션 크기 | 평가 |
|--------|-------------|------|
| `createOrder` | 중간 (여러 상품 조회 + 재고 차감 + 포인트 차감 + 주문 저장) | ✅ 적절 |
| `cancelOrder` | 중간 (재고 원복 + 포인트 환불 + 주문 취소) | ✅ 적절 |
| `addLike` | 작음 (조회 + 저장) | ✅ 적절 |
| `removeLike` | 작음 (조회 + 삭제) | ✅ 적절 |
| `getOrders` | 작음 (조회만) | ⚠️ readOnly 추가 필요 |
| `getOrder` | 작음 (조회만) | ⚠️ readOnly 추가 필요 |

**결론**: ✅ **트랜잭션 크기가 적절함** (너무 크거나 작지 않음)

---

### 3. 자동 커밋 설정 확인

#### 현재 설정

**Spring의 기본 동작**:
- `@Transactional`이 있으면 자동 커밋 비활성화
- 트랜잭션 범위 내에서 모든 작업이 하나의 커밋으로 처리

**확인 결과**:
- ✅ **자동 커밋 문제 없음**: 모든 Facade 메서드에 `@Transactional` 적용
- ✅ **트랜잭션 범위 적절**: 하나의 유즈케이스 단위로 묶음

---

### 4. 대량 수정 패턴 분석

#### 현재 패턴

**1. 재고 차감 (여러 상품)**
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 여러 상품의 재고를 차감
    for (OrderItemCommand command : commands) {
        Product product = productRepository.findByIdForUpdate(command.productId());
        product.decreaseStock(command.quantity());
        productRepository.save(product);  // 각각 UPDATE
    }
    // → 하나의 트랜잭션으로 묶여 있음 ✅
}
```

**평가**:
- ✅ **하나의 트랜잭션으로 묶음**: 로그 I/O 1번
- ⚠️ **개별 UPDATE**: 여러 UPDATE 쿼리 (로그 레코드는 많지만 커밋은 1번)

**2. 포인트 차감**
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 포인트 차감
    user.deductPoint(Point.of(totalAmount));
    userRepository.save(user);  // 1번의 UPDATE
    // → 하나의 트랜잭션으로 묶여 있음 ✅
}
```

**평가**:
- ✅ **1번의 UPDATE**: 로그 레코드 최소화

---

## ⚠️ 개선 방안

### 1. 읽기 전용 트랜잭션 명시

#### 개선 대상

**PurchasingFacade.getOrders**
```java
// ❌ 현재
@Transactional
public List<OrderInfo> getOrders(String userId) {
    // ...
}

// ✅ 개선
@Transactional(readOnly = true)
public List<OrderInfo> getOrders(String userId) {
    // ...
}
```

**PurchasingFacade.getOrder**
```java
// ❌ 현재
@Transactional
public OrderInfo getOrder(String userId, Long orderId) {
    // ...
}

// ✅ 개선
@Transactional(readOnly = true)
public OrderInfo getOrder(String userId, Long orderId) {
    // ...
}
```

**LikeFacade.getLikedProducts**
```java
// ❌ 현재
public List<LikedProduct> getLikedProducts(String userId) {
    // ...
}

// ✅ 개선
@Transactional(readOnly = true)
public List<LikedProduct> getLikedProducts(String userId) {
    // ...
}
```

**효과**:
- ✅ 불필요한 쓰기 락 설정 방지
- ✅ 로그 버퍼에 불필요한 정보 기록 방지
- ✅ 읽기 일관성 보장

---

### 2. 트랜잭션 범위 최소화 (현재는 이미 적절함)

#### 현재 상태

**✅ 이미 적절함**:
- 하나의 유즈케이스 단위로 트랜잭션 묶음
- 트랜잭션을 잘게 나누지 않음
- 커밋 I/O 최소화

**❌ 잘못된 예시 (개선하지 않음)**:
```java
// ❌ 트랜잭션을 잘게 나눔 (성능 저하)
public void createOrder(String userId, List<OrderItemCommand> commands) {
    // 트랜잭션 1: 사용자 조회
    @Transactional
    User user = loadUserForUpdate(userId);
    
    // 트랜잭션 2: 상품 조회
    @Transactional
    List<Product> products = loadProducts(commands);
    
    // 트랜잭션 3: 재고 차감
    @Transactional
    decreaseStocks(products);
    
    // 트랜잭션 4: 포인트 차감
    @Transactional
    deductPoint(user);
    
    // 트랜잭션 5: 주문 저장
    @Transactional
    saveOrder(order);
    
    // → 커밋 I/O 5번 발생! (성능 저하)
}
```

**현재 프로젝트는 이 문제가 없음** ✅

---

### 3. 대량 수정 시 배치 처리 고려 (향후 개선)

#### 현재 패턴

**여러 상품 재고 차감**:
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    for (OrderItemCommand command : commands) {
        Product product = productRepository.findByIdForUpdate(command.productId());
        product.decreaseStock(command.quantity());
        productRepository.save(product);  // 각각 UPDATE
    }
}
```

**평가**:
- ✅ **하나의 트랜잭션**: 커밋 I/O 1번
- ⚠️ **여러 UPDATE 쿼리**: 로그 레코드는 많지만 커밋은 1번

**향후 개선 방안** (대량 주문 시):
```java
@Transactional
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // 배치 UPDATE 고려 (대량 주문 시)
    if (commands.size() > 10) {
        // 배치 UPDATE 사용
        productRepository.batchUpdateStock(commands);
    } else {
        // 현재 방식 유지
        for (OrderItemCommand command : commands) {
            // ...
        }
    }
}
```

**현재는 개선 불필요** (주문 아이템이 많지 않음)

---

### 4. 로그 I/O 병목 모니터링

#### 모니터링 지표

1. **트랜잭션 커밋 횟수**: 최소화 필요
2. **로그 I/O 대기 시간**: 모니터링 필요
3. **동시 트랜잭션 수**: 로그 I/O 병목 감지

#### 현재 프로젝트 상태

**✅ 트랜잭션 커밋 횟수 최소화**:
- 하나의 유즈케이스 단위로 트랜잭션 묶음
- 불필요한 커밋 없음

**⚠️ 향후 모니터링 필요**:
- 대량 트래픽 시 로그 I/O 병목 모니터링
- TPS 저하 시 로그 I/O 원인 확인

---

## 📊 종합 평가

### 현재 프로젝트 구조 평가표

| 항목 | 평가 | 점수 | 설명 |
|------|------|------|------|
| **트랜잭션 범위** | ✅ 적절 | 9/10 | 하나의 유즈케이스 단위로 묶음 |
| **트랜잭션 크기** | ✅ 적절 | 9/10 | 너무 크거나 작지 않음 |
| **자동 커밋 방지** | ✅ 적절 | 10/10 | 모든 Facade에 @Transactional 적용 |
| **읽기 전용 트랜잭션** | ⚠️ 개선 필요 | 6/10 | 일부 메서드에 readOnly = true 없음 |
| **커밋 I/O 최소화** | ✅ 적절 | 10/10 | 하나의 유즈케이스당 1번의 커밋 |
| **대량 수정 패턴** | ✅ 적절 | 8/10 | 현재 규모에서는 적절, 향후 배치 고려 |

**종합 점수**: **52/60 (87점)** ✅

### 핵심 강점

1. ✅ **트랜잭션 범위 적절**: 하나의 유즈케이스 단위로 묶음
2. ✅ **커밋 I/O 최소화**: 하나의 유즈케이스당 1번의 커밋
3. ✅ **자동 커밋 방지**: 모든 Facade에 @Transactional 적용
4. ✅ **트랜잭션을 잘게 나누지 않음**: 성능 저하 방지

### 개선 필요 사항

1. ⚠️ **읽기 전용 트랜잭션 명시**: `@Transactional(readOnly = true)` 추가
2. ⚠️ **로그 I/O 모니터링**: 향후 대량 트래픽 시 모니터링 필요

---

## 🎯 개선 작업

### 1. 읽기 전용 트랜잭션 추가

#### PurchasingFacade.getOrders

```java
@Transactional(readOnly = true)  // ✅ 개선
public List<OrderInfo> getOrders(String userId) {
    User user = loadUser(userId);
    List<Order> orders = orderRepository.findAllByUserId(user.getId());
    return orders.stream()
        .map(OrderInfo::from)
        .toList();
}
```

#### PurchasingFacade.getOrder

```java
@Transactional(readOnly = true)  // ✅ 개선
public OrderInfo getOrder(String userId, Long orderId) {
    User user = loadUser(userId);
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    return OrderInfo.from(order);
}
```

#### LikeFacade.getLikedProducts

```java
@Transactional(readOnly = true)  // ✅ 개선
public List<LikedProduct> getLikedProducts(String userId) {
    User user = loadUser(userId);
    // ...
}
```

---

## 📝 결론

### 현재 프로젝트의 WAL 성능은 **매우 양호**합니다 ✅

**이유**:
1. ✅ **트랜잭션 범위 적절**: 하나의 유즈케이스 단위로 묶음
2. ✅ **커밋 I/O 최소화**: 하나의 유즈케이스당 1번의 커밋
3. ✅ **자동 커밋 방지**: 모든 Facade에 @Transactional 적용
4. ✅ **트랜잭션을 잘게 나누지 않음**: 성능 저하 방지

**개선 사항**:
1. ⚠️ **읽기 전용 트랜잭션 명시**: `@Transactional(readOnly = true)` 추가 (성능 향상)
2. ⚠️ **로그 I/O 모니터링**: 향후 대량 트래픽 시 모니터링 필요

**과제 적용**:
- ✅ "트랜잭션을 남발하면 성능이 떨어지는 이유" 설명 가능
- ✅ "트랜잭션을 잘게 나누면 더 빠르지 않을까?" → ❌ 오히려 느려짐 설명 가능
- ✅ "트랜잭션 범위를 서비스 레이어에서 일관되게 관리해야 하는 이유" 근거 제공
- ✅ "읽기보다 쓰기 트랜잭션이 더 위험한 이유" 설명 가능

---

## 🔗 관련 문서

- [Lock 전략 설계](./09-lock-strategy.md)
- [락 설계 평가](./13-lock-design-evaluation.md)
- [트랜잭션 격리 수준 분석](./transaction-isolation-analysis.md)

