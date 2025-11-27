# created_at DESC 인덱스 추가 분석

## 현재 상황

### 기존 인덱스
- ✅ `idx_product_brand_created`: `(ref_brand_id, created_at)` - 브랜드 필터 + 최신순 정렬
- ✅ `idx_product_likes`: `(like_count)` - 전체 조회 + 좋아요 순 정렬
- ✅ `idx_product_price`: `(price)` - 전체 조회 + 가격순 정렬
- ❌ **`created_at` 단독 인덱스 없음** - 전체 조회 + 최신순 정렬용

### 실제 쿼리 패턴

#### 1. 전체 조회 + 최신순 정렬 (기본값)
```sql
-- sort="latest" 또는 sort 미지정
SELECT * FROM product 
ORDER BY created_at DESC 
LIMIT 20 OFFSET 0;
```

**현재 상태:**
- 브랜드 필터가 없으므로 `idx_product_brand_created` 사용 불가
- `created_at` 단독 인덱스가 없음
- **전체 테이블 스캔 + 파일 정렬 발생 가능**

#### 2. 브랜드 필터 + 최신순 정렬
```sql
-- brandId가 있는 경우
SELECT * FROM product 
WHERE ref_brand_id = ? 
ORDER BY created_at DESC 
LIMIT 20 OFFSET 0;
```

**현재 상태:**
- ✅ `idx_product_brand_created` 사용 가능
- 정상 동작

## 분석 결과

### 인덱스 추가가 필요한 이유

1. **전체 조회가 빈번함**
   - 부하 테스트에서 `/api/v1/products` (전체 조회)가 가장 많이 호출됨
   - 기본 정렬이 `latest` (최신순)

2. **현재 인덱스로는 커버되지 않음**
   - `idx_product_brand_created`는 브랜드 필터가 있을 때만 사용
   - 전체 조회 시 인덱스 미사용 → 전체 테이블 스캔

3. **응답 시간 개선 필요**
   - 현재 평균 응답 시간: 18초
   - 인덱스 추가로 대폭 개선 가능

### 인덱스 추가 효과

**추가 전:**
```sql
EXPLAIN SELECT * FROM product ORDER BY created_at DESC LIMIT 20;
```
예상 결과:
- `type`: `ALL` (전체 테이블 스캔)
- `key`: `NULL` (인덱스 미사용)
- `rows`: 전체 행 수 (예: 100,000)
- `Extra`: `Using filesort` (파일 정렬)

**추가 후:**
```sql
EXPLAIN SELECT * FROM product ORDER BY created_at DESC LIMIT 20;
```
예상 결과:
- `type`: `index` (인덱스 스캔)
- `key`: `idx_product_created` (인덱스 사용)
- `rows`: 20 (LIMIT만큼만 스캔)
- `Extra`: `Using index` (인덱스만으로 처리)

**예상 성능 개선:**
- 전체 테이블 스캔 (100,000행) → 인덱스 스캔 (20행)
- 파일 정렬 제거
- 응답 시간: 18초 → 0.1-0.5초 (약 36-180배 개선)

## 권장 사항

### ✅ 인덱스 추가 권장

**이유:**
1. 전체 조회가 가장 빈번한 쿼리
2. 기본 정렬이 최신순 (`latest`)
3. 현재 인덱스로 커버되지 않음
4. 성능 개선 효과가 큼

### 인덱스 생성 방법

#### 방법 1: SQL 스크립트 추가
```sql
-- 전체 조회 + 최신순 정렬 최적화
CREATE INDEX IF NOT EXISTS idx_product_created 
ON product(created_at DESC);
```

#### 방법 2: JPA 엔티티에 추가
```java
@Table(
    name = "product",
    indexes = {
        // ... 기존 인덱스들
        // 전체 조회 + 최신순 정렬 최적화
        @Index(name = "idx_product_created", columnList = "created_at")
    }
)
```

**참고:** MySQL은 인덱스 생성 시 DESC를 명시해도 내부적으로는 동일하게 처리됩니다. 하지만 명시하는 것이 의도를 명확히 합니다.

### 주의사항

1. **인덱스 유지 비용**
   - INSERT/UPDATE 시 약간의 오버헤드
   - 저장 공간 약간 증가
   - 하지만 읽기 성능 향상이 훨씬 큼

2. **기존 인덱스와의 관계**
   - `idx_product_brand_created`와 중복되지 않음
   - 브랜드 필터 있을 때: `idx_product_brand_created` 사용
   - 브랜드 필터 없을 때: `idx_product_created` 사용

## 결론

**`created_at DESC` 인덱스 추가를 강력히 권장합니다.**

- 전체 조회가 가장 빈번한 쿼리
- 현재 인덱스로 커버되지 않음
- 성능 개선 효과가 매우 큼 (18초 → 0.1-0.5초)
- 다른 인덱스와 충돌 없음

### 우선순위
1. **캐시 전략 개선** (가장 효과적)
2. **`created_at` 인덱스 추가** (즉시 효과)
3. **Connection Pool 크기 조정** (캐시/인덱스 개선 후)

