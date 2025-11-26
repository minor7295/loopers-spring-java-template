# Sorted Set을 사용한 첫 페이지 캐시 방안 평가

## 현재 구현

### 캐시 방식
- **방식**: JSON 문자열로 전체 결과 캐시
- **키 구조**: `product:list:brand:all:sort:{sort}:page:0:size:20`
- **저장 내용**: `ProductInfoList` 전체를 JSON으로 직렬화
- **정렬 기준**: `latest`, `price_asc`, `likes_desc`

### 정렬 기준별 처리
- `latest`: `created_at DESC` (타임스탬프 내림차순)
- `price_asc`: `price ASC` (가격 오름차순)
- `likes_desc`: `like_count DESC` (좋아요 수 내림차순)

## Sorted Set 캐시 방안

### 제안 구조
```
키: product:list:all:sort:{sort}
타입: Sorted Set (ZSET)
Score: 정렬 기준 값
  - latest: created_at timestamp (내림차순이므로 음수 또는 MAX - timestamp)
  - price_asc: price 값
  - likes_desc: like_count 값 (내림차순이므로 음수 또는 MAX - like_count)
Member: productId
```

### 예시
```redis
# latest 정렬
ZADD product:list:all:sort:latest -1735689600000 1  # productId=1, created_at=2025-01-01
ZADD product:list:all:sort:latest -1735603200000 2  # productId=2, created_at=2025-01-02

# price_asc 정렬
ZADD product:list:all:sort:price_asc 10000 1  # productId=1, price=10000
ZADD product:list:all:sort:price_asc 20000 2  # productId=2, price=20000

# likes_desc 정렬
ZADD product:list:all:sort:likes_desc -100 1  # productId=1, like_count=100 (내림차순)
ZADD product:list:all:sort:likes_desc -200 2  # productId=2, like_count=200
```

### 조회 방식
```redis
# 첫 20개 조회
ZRANGE product:list:all:sort:latest 0 19  # latest
ZRANGE product:list:all:sort:price_asc 0 19  # price_asc
ZREVRANGE product:list:all:sort:likes_desc 0 19  # likes_desc (내림차순)
```

## 장점 분석

### ✅ 1. 메모리 효율성
- **현재**: 전체 JSON 문자열 저장 (약 5-10KB)
- **Sorted Set**: productId만 저장 (약 20개 × 8바이트 = 160바이트)
- **개선**: 약 30-60배 메모리 절약

### ✅ 2. 부분 업데이트 가능
- **현재**: 상품 변경 시 전체 캐시 무효화 필요
- **Sorted Set**: 개별 상품만 업데이트 가능
  ```redis
  # 좋아요 수 변경 시
  ZADD product:list:all:sort:likes_desc -150 1  # productId=1의 좋아요 수 업데이트
  ```

### ✅ 3. 실시간 정렬 반영
- **현재**: TTL 만료 또는 명시적 무효화 필요
- **Sorted Set**: Score 업데이트로 즉시 반영

### ✅ 4. Redis 네이티브 정렬
- **현재**: DB에서 정렬 후 캐시
- **Sorted Set**: Redis에서 정렬 (O(log N) 조회)

### ✅ 5. 확장성
- **현재**: 페이지별로 별도 키 필요
- **Sorted Set**: 하나의 Set으로 여러 페이지 지원 가능
  ```redis
  ZRANGE product:list:all:sort:latest 0 19   # 첫 페이지
  ZRANGE product:list:all:sort:latest 20 39  # 두 번째 페이지
  ```

## 단점 분석

### ❌ 1. 추가 조회 필요
- **현재**: 캐시에서 바로 `ProductInfoList` 반환
- **Sorted Set**: 
  1. Sorted Set에서 productId 목록 조회
  2. 각 productId로 상품 상세 정보 조회 (MGET 또는 개별 조회)
  3. 브랜드 정보 조회
  4. 결과 조합
- **네트워크 왕복**: 1회 → 2-3회 증가

### ❌ 2. 복잡도 증가
- **현재**: 단순 GET/SET
- **Sorted Set**: 
  - 캐시 저장: DB 조회 → Sorted Set 구성 → 상품 상세 캐시 확인/저장
  - 캐시 조회: Sorted Set 조회 → 상품 상세 조회 → 브랜드 조회 → 조합
- **코드 복잡도**: 크게 증가

### ❌ 3. 데이터 일관성
- **현재**: 하나의 JSON으로 원자적 저장
- **Sorted Set**: 
  - Sorted Set과 상품 상세 캐시 간 불일치 가능
  - 상품이 삭제되었는데 Sorted Set에 남아있을 수 있음
- **동기화 문제**: 추가 관리 필요

### ❌ 4. Score 계산 복잡도
- **latest**: 타임스탬프 변환 필요 (밀리초 → 음수 또는 MAX - 값)
- **price_asc**: 직접 사용 가능
- **likes_desc**: 음수 변환 필요
- **Score 충돌**: 동일한 Score 값 처리 필요

### ❌ 5. 브랜드 정보 누락
- **현재**: JSON에 브랜드 정보 포함
- **Sorted Set**: productId만 저장, 브랜드 정보 별도 조회 필요
- **추가 조회**: 브랜드 정보를 위한 추가 조회 필요

### ❌ 6. 전체 상품 수 관리
- **현재**: `ProductInfoList`에 `totalCount` 포함
- **Sorted Set**: `ZCARD`로 개수 조회 가능하지만, 캐시된 상품 수와 실제 DB 개수 불일치 가능

### ❌ 7. 캐시 무효화 복잡도
- **현재**: 키 삭제만 하면 됨
- **Sorted Set**: 
  - 상품 추가/삭제: Sorted Set 업데이트 필요
  - 정렬 기준 변경: Score 재계산 필요
  - 부분 업데이트: 개별 상품만 업데이트 가능하지만 로직 복잡

## 성능 비교

### 현재 방식 (JSON 캐시)
```
조회: GET key → JSON 역직렬화 → 반환
시간: O(1) + 역직렬화 시간
네트워크: 1회
```

### Sorted Set 방식
```
조회: ZRANGE key → MGET product:detail:* → 조합
시간: O(log N + M) (N=전체 상품 수, M=조회할 상품 수)
네트워크: 2-3회 (Sorted Set 조회 + 상품 상세 조회 + 브랜드 조회)
```

## 적절성 평가

### ✅ 적합한 경우
1. **메모리가 매우 제한적인 경우**
2. **부분 업데이트가 빈번한 경우** (예: 좋아요 수 실시간 업데이트)
3. **여러 페이지를 캐시해야 하는 경우**
4. **정렬 기준이 단순한 경우** (숫자 또는 타임스탬프)

### ❌ 부적합한 경우 (현재 프로젝트)
1. **첫 페이지만 캐시**: 확장성 이점 활용 불가
2. **복잡도 증가**: 코드 복잡도 대비 이점 적음
3. **추가 조회 필요**: 네트워크 왕복 증가
4. **데이터 일관성**: 관리 복잡도 증가
5. **현재 요구사항**: 단순한 캐시 전략으로 충분

## 결론

### 권장사항: ❌ **현재 방식 유지**

**이유**:
1. **첫 페이지만 캐시**: 메모리 절약 효과가 크지 않음 (20개만 저장)
2. **복잡도 대비 이점 적음**: 코드 복잡도가 크게 증가하지만 이점이 제한적
3. **네트워크 왕복 증가**: 성능 저하 가능
4. **데이터 일관성 관리**: 추가 복잡도
5. **현재 요구사항 충족**: 현재 방식으로 충분

### 대안: 하이브리드 방식 (선택적)

만약 메모리 최적화가 필요하다면:
- **Sorted Set**: productId만 저장 (정렬 순서)
- **Hash**: 상품 상세 정보 저장 (product:detail:{id})
- **조회**: Sorted Set → Hash 조회 → 조합

하지만 현재 요구사항에서는 **과도한 최적화**로 판단됩니다.

## 최종 평가

| 항목 | 현재 방식 | Sorted Set | 평가 |
|------|----------|------------|------|
| 메모리 효율성 | ⚠️ 보통 | ✅ 우수 | Sorted Set 승 |
| 조회 성능 | ✅ 우수 | ⚠️ 보통 | 현재 방식 승 |
| 코드 복잡도 | ✅ 단순 | ❌ 복잡 | 현재 방식 승 |
| 데이터 일관성 | ✅ 우수 | ⚠️ 보통 | 현재 방식 승 |
| 유지보수성 | ✅ 우수 | ⚠️ 보통 | 현재 방식 승 |
| 확장성 | ⚠️ 제한적 | ✅ 우수 | Sorted Set 승 |

**종합 평가**: 현재 방식이 더 적합합니다.

