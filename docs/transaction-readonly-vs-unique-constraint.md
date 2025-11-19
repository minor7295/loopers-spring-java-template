# @Transactional(readOnly = true) vs UNIQUE 제약조건

## 핵심 차이점

### UNIQUE 제약조건
- **목적**: 데이터 무결성 보장 (중복 데이터 방지)
- **작동 방식**: 데이터베이스 레벨에서 중복 삽입을 물리적으로 방지
- **예시**: `LikeFacade.addLike()`에서 동일한 사용자가 동일한 상품에 좋아요를 중복으로 추가하는 것을 방지

### @Transactional(readOnly = true)
- **목적**: 여러 쿼리 간의 논리적 일관성 보장
- **작동 방식**: 모든 쿼리가 동일한 트랜잭션 내에서 실행되어 일관된 스냅샷을 봄
- **예시**: `LikeFacade.getLikedProducts()`에서 좋아요 목록 조회와 집계 쿼리가 동일한 시점의 데이터를 봄

## REPEATABLE READ 격리 수준에서의 동작

### 트랜잭션이 없는 경우
```java
// 트랜잭션 없이 실행
public List<LikedProduct> getLikedProducts(String userId) {
    // 쿼리 1: 좋아요 목록 조회 - 시점 T1의 스냅샷
    List<Like> likes = likeRepository.findAllByUserId(user.getId());
    
    // 다른 트랜잭션이 좋아요 추가 (커밋됨)
    
    // 쿼리 2: 좋아요 수 집계 - 시점 T2의 스냅샷 (T1과 다를 수 있음)
    Map<Long, Long> likesCountMap = likeRepository.countByProductIds(productIds);
}
```

**REPEATABLE READ의 특성**:
- 각 쿼리가 자체적으로 일관된 스냅샷을 봄
- 쿼리 1과 쿼리 2가 서로 다른 시점의 스냅샷을 볼 수 있음
- 하지만 각 쿼리 내에서는 일관성이 보장됨

### 트랜잭션이 있는 경우
```java
@Transactional(readOnly = true)
public List<LikedProduct> getLikedProducts(String userId) {
    // 쿼리 1: 좋아요 목록 조회 - 트랜잭션 시작 시점의 스냅샷
    List<Like> likes = likeRepository.findAllByUserId(user.getId());
    
    // 다른 트랜잭션이 좋아요 추가 (커밋됨)
    
    // 쿼리 2: 좋아요 수 집계 - 동일한 트랜잭션 시작 시점의 스냅샷
    Map<Long, Long> likesCountMap = likeRepository.countByProductIds(productIds);
}
```

**트랜잭션의 효과**:
- 모든 쿼리가 동일한 트랜잭션 내에서 실행됨
- 모든 쿼리가 동일한 시점의 스냅샷을 봄
- 논리적 일관성 보장

## 실제 문제 시나리오

### 시나리오 1: 좋아요 목록과 집계 결과의 불일치

**트랜잭션 없이 실행**:
```
T1 (조회 스레드):
  1. 좋아요 목록 조회 → [상품1, 상품2] (각 1개씩)
  2. (다른 트랜잭션이 상품1에 좋아요 추가)
  3. 좋아요 수 집계 → 상품1: 2개, 상품2: 1개
  
결과: 좋아요 목록에는 상품1이 1개로 보이지만, 집계 결과는 2개
```

**트랜잭션 있이 실행**:
```
T1 (조회 스레드):
  1. 트랜잭션 시작 (스냅샷 시점: T0)
  2. 좋아요 목록 조회 → [상품1, 상품2] (각 1개씩) - T0 시점
  3. (다른 트랜잭션이 상품1에 좋아요 추가)
  4. 좋아요 수 집계 → 상품1: 1개, 상품2: 1개 - T0 시점
  
결과: 좋아요 목록과 집계 결과가 일관됨
```

## 왜 테스트가 통과하는가?

REPEATABLE READ 격리 수준에서는:
1. 각 쿼리가 자체적으로 일관된 스냅샷을 봄
2. 따라서 각 쿼리 내에서는 일관성이 보장됨
3. 하지만 여러 쿼리 간의 논리적 일관성은 보장되지 않을 수 있음

**실제로 문제가 드문 이유**:
- 쿼리 실행 시간이 매우 짧음
- 다른 트랜잭션이 정확히 중간에 개입할 확률이 낮음
- REPEATABLE READ의 특성상 각 쿼리가 자체 스냅샷을 보기 때문

## 결론

### UNIQUE 제약조건
- **필수**: 데이터 무결성 보장을 위해 반드시 필요
- **역할**: 중복 데이터 방지

### @Transactional(readOnly = true)
- **권장**: 여러 쿼리 간의 논리적 일관성을 보장하기 위해 권장
- **역할**: 
  1. 모든 쿼리가 동일한 시점의 스냅샷을 보도록 보장
  2. 읽기 전용 트랜잭션임을 명시적으로 표현
  3. 성능 최적화 (쓰기 락 미사용)

**답변**: UNIQUE 제약조건이 있어도 `@Transactional(readOnly = true)`는 여전히 필요합니다. 서로 다른 목적을 가지고 있으며, 함께 사용하는 것이 좋습니다.

