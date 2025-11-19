# 트랜잭션 격리 수준 및 동시성 문제 분석

## 현재 설정

- **DB 격리 수준**: `REPEATABLE READ` (MySQL InnoDB 기본값)
- **명시적 설정**: 없음 (기본값 사용)

## 격리 수준별 문제 발생 가능성

### 1. Dirty Read (더티 리드)
**발생 가능성: ❌ 없음**

REPEATABLE READ 격리 수준에서는 커밋되지 않은 데이터를 읽을 수 없으므로 Dirty Read는 발생하지 않습니다.

### 2. Non-repeatable Read (비반복 읽기)
**발생 가능성: ⚠️ 제한적 (일부 쿼리에서 가능)**

REPEATABLE READ는 동일 트랜잭션 내에서 동일한 행을 여러 번 읽을 때 일관성을 보장합니다. 하지만 다음 경우에 문제가 될 수 있습니다:

#### ⚠️ 잠재적 문제 지점

1. **`PurchasingFacade.cancelOrder()`** (Line 105-124)
   ```java
   @Transactional
   public void cancelOrder(Order order, User user) {
       // 일반 조회 (락 없음)
       List<Product> products = order.getItems().stream()
           .map(item -> productRepository.findById(item.getProductId()) // 락 없음
           ...
   ```
   - **문제**: 주문 취소 중 다른 트랜잭션이 상품 재고를 변경할 수 있음
   - **영향**: 재고 원복 시 잘못된 값으로 업데이트될 가능성
   - **현재 보완**: 트랜잭션 내에서 빠르게 처리되므로 실제 발생 가능성은 낮음

2. **`LikeFacade.getLikedProducts()`** (Line 108-145)
   ```java
   public List<LikedProduct> getLikedProducts(String userId) {
       // 트랜잭션 없이 조회
       List<Like> likes = likeRepository.findAllByUserId(user.getId());
       // 이후 상품 조회 및 집계
   ```
   - **문제**: 트랜잭션이 없어 일관성 보장 안 됨
   - **영향**: 좋아요 목록과 상품 정보 간 불일치 가능

### 3. Phantom Read (팬텀 리드)
**발생 가능성: ⚠️ 있음 (범위 쿼리/집계 쿼리에서)**

REPEATABLE READ는 기존 행의 일관성만 보장하고, 새로운 행의 삽입은 방지하지 않습니다.

#### ⚠️ 잠재적 문제 지점

1. **`LikeFacade.getLikedProducts()` - 집계 쿼리** (Line 131)
   ```java
   Map<Long, Long> likesCountMap = likeRepository.countByProductIds(productIds);
   ```
   - **문제**: 조회 중간에 다른 트랜잭션이 좋아요를 추가/삭제하면 집계 결과가 달라질 수 있음
   - **시나리오**:
     ```
     T1: 좋아요 목록 조회 (productIds: [1, 2, 3])
     T2: 상품 1에 좋아요 추가 (커밋)
     T1: 좋아요 수 집계 → 상품 1의 좋아요 수가 T1 시작 시점과 다를 수 있음
     ```
   - **영향**: 사용자에게 표시되는 좋아요 수가 실제와 다를 수 있음

2. **`LikeRepository.countByProductIds()` - GROUP BY 쿼리**
   ```java
   @Query("SELECT l.productId, COUNT(l) FROM Like l WHERE l.productId IN :productIds GROUP BY l.productId")
   ```
   - **문제**: 범위 쿼리이므로 Phantom Read 발생 가능
   - **영향**: 집계 결과의 정확성 저하

3. **`ProductRepository.findAll()` - 페이징 쿼리**
   ```java
   Page<Product> findByBrandId(Long brandId, Pageable pageable);
   ```
   - **문제**: 페이징 중간에 상품이 추가/삭제되면 페이지 경계에서 중복/누락 발생 가능
   - **영향**: 상품 목록 조회 시 일관성 문제

## 현재 보호 메커니즘

### ✅ 잘 보호된 부분

1. **`PurchasingFacade.createOrder()`**
   - `findByUserIdForUpdate()`: 비관적 락 사용
   - `findByIdForUpdate()`: 비관적 락 사용
   - 포인트 차감 및 재고 차감 시 동시성 제어 보장

2. **`LikeFacade.addLike()`**
   - UNIQUE 제약조건으로 중복 방지
   - 예외 처리로 멱등성 보장

### ⚠️ 개선이 필요한 부분

1. **`LikeFacade.getLikedProducts()`**
   - 트랜잭션 없이 조회하여 일관성 보장 안 됨
   - 집계 쿼리에서 Phantom Read 가능

2. **`PurchasingFacade.cancelOrder()`**
   - 상품 조회 시 락 미사용
   - 재고 원복 시 정확성 보장 안 됨

3. **`PurchasingFacade.getOrders()`, `getOrder()`**
   - 읽기 전용 트랜잭션이지만 일관성 보장은 제한적

## 권장 개선 사항

### 1. `LikeFacade.getLikedProducts()` 개선
```java
@Transactional(readOnly = true)
public List<LikedProduct> getLikedProducts(String userId) {
    // 트랜잭션 내에서 일관된 스냅샷 보장
    ...
}
```

### 2. `PurchasingFacade.cancelOrder()` 개선
```java
@Transactional
public void cancelOrder(Order order, User user) {
    // 비관적 락 사용
    List<Product> products = order.getItems().stream()
        .map(item -> productRepository.findByIdForUpdate(item.getProductId())
        ...
}
```

### 3. 집계 쿼리 개선 (선택적)
- Phantom Read를 완전히 방지하려면 `SERIALIZABLE` 격리 수준 필요
- 하지만 성능 저하가 크므로, 실시간 정확성이 중요하지 않은 경우 현재 수준 유지 가능

## 격리 수준 변경 고려사항

### SERIALIZABLE로 변경 시
- **장점**: 모든 동시성 문제 완전 방지
- **단점**: 
  - 성능 저하 (락 대기 시간 증가)
  - 데드락 발생 가능성 증가
  - 동시성 처리량 감소

### 현재 REPEATABLE READ 유지 시
- **장점**: 성능과 일관성의 균형
- **단점**: Phantom Read 가능 (범위 쿼리에서)
- **권장**: 비관적 락을 적절히 사용하여 핵심 비즈니스 로직 보호

## 결론

현재 프로젝트는 **REPEATABLE READ 격리 수준**을 사용하고 있으며:

1. **Dirty Read**: 발생 불가능 ✅
2. **Non-repeatable Read**: 대부분 방지되지만 일부 쿼리에서 개선 필요 ⚠️
3. **Phantom Read**: 집계 쿼리 및 범위 쿼리에서 발생 가능 ⚠️

핵심 비즈니스 로직(주문 생성, 포인트 차감, 재고 차감)은 비관적 락으로 잘 보호되고 있으나, 조회 및 집계 쿼리에서 일관성 개선이 필요합니다.

