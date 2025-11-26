# 인덱스 성능 비교 테스트 가이드

## 개요

브랜드 필터 + 최신순 정렬 쿼리의 성능을 3가지 인덱스 시나리오별로 직접 SQL을 실행하여 비교합니다.

## 테스트 시나리오

### 시나리오 1: 인덱스 없음
- **상태**: 모든 인덱스 제거 (PK 제외)
- **예상 결과**: 전체 테이블 스캔 + 파일 정렬
- **예상 성능**: 가장 느림 (~500ms)

### 시나리오 2: 단일 인덱스 (ref_brand_id)
- **인덱스**: `CREATE INDEX idx_product_brand_id ON product(ref_brand_id);`
- **예상 결과**: 브랜드 필터링은 빠르지만 정렬은 파일 정렬 필요
- **예상 성능**: 중간 (~100ms)

### 시나리오 3: 복합 인덱스 (ref_brand_id, created_at)
- **인덱스**: `CREATE INDEX idx_product_brand_created ON product(ref_brand_id, created_at);`
- **예상 결과**: 브랜드 필터링 + 정렬 모두 인덱스로 처리
- **예상 성능**: 가장 빠름 (~10ms)

## 테스트 실행 방법

### 1. 데이터 준비

```bash
# 데이터 시딩 실행 (100,000개 상품 생성)
./gradlew :apps:commerce-api:runSeeding
```

### 2. MySQL 클라이언트 접속

```bash
mysql -u application -p loopers
```

### 3. SQL 스크립트 실행

```bash
# 방법 1: 파일로 실행
mysql -u application -p loopers < apps/commerce-api/docs/performance/index-comparison-test.sql

# 방법 2: MySQL 클라이언트에서 복사/붙여넣기
# index-comparison-test.sql 파일의 내용을 복사하여 실행
```

### 4. 단계별 실행 (권장)

SQL 스크립트를 단계별로 나누어 실행하는 것을 권장합니다:

#### Step 1: 데이터 확인
```sql
SELECT COUNT(*) as total_products FROM product;
SELECT ref_brand_id, COUNT(*) as product_count 
FROM product 
GROUP BY ref_brand_id 
LIMIT 10;
```

#### Step 2: 시나리오 1 - 인덱스 없음
```sql
-- 모든 인덱스 제거
DROP INDEX IF EXISTS idx_product_brand_id ON product;
DROP INDEX IF EXISTS idx_product_brand_created ON product;
-- ... (기타 인덱스 제거)

-- EXPLAIN 분석
EXPLAIN 
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = 1 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;

-- 성능 측정 (여러 번 실행)
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = 1 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;
```

#### Step 3: 시나리오 2 - 단일 인덱스
```sql
-- 단일 인덱스 생성
CREATE INDEX idx_product_brand_id ON product(ref_brand_id);

-- EXPLAIN 분석
EXPLAIN 
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = 1 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;

-- 성능 측정
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = 1 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;
```

#### Step 4: 시나리오 3 - 복합 인덱스
```sql
-- 단일 인덱스 제거 후 복합 인덱스 생성
DROP INDEX idx_product_brand_id ON product;
CREATE INDEX idx_product_brand_created ON product(ref_brand_id, created_at);

-- EXPLAIN 분석
EXPLAIN 
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = 1 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;

-- 성능 측정
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = 1 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;
```

## 결과 분석

### EXPLAIN 결과 비교

| 시나리오 | type | key | rows | Extra |
|---------|------|-----|------|-------|
| 인덱스 없음 | ALL | NULL | 100,000 | Using where; Using filesort |
| 단일 인덱스 | ref | idx_product_brand_id | 10,000 | Using filesort |
| 복합 인덱스 | ref | idx_product_brand_created | 20 | Using index condition |

### 성능 측정 결과 기록

각 시나리오별로 10회 실행하여 평균값을 계산합니다:

```
시나리오 1 (인덱스 없음):
실행 1: 500ms
실행 2: 520ms
실행 3: 480ms
...
평균: ~500ms

시나리오 2 (단일 인덱스):
실행 1: 100ms
실행 2: 95ms
실행 3: 105ms
...
평균: ~100ms

시나리오 3 (복합 인덱스):
실행 1: 10ms
실행 2: 12ms
실행 3: 8ms
...
평균: ~10ms
```

### 성능 개선율 계산

```
단일 인덱스 vs 인덱스 없음:
개선율 = (500 - 100) / 500 * 100 = 80% 개선

복합 인덱스 vs 인덱스 없음:
개선율 = (500 - 10) / 500 * 100 = 98% 개선

복합 인덱스 vs 단일 인덱스:
개선율 = (100 - 10) / 100 * 100 = 90% 개선
```

## 주의사항

### 1. 캐시 무효화
MySQL 8.0 이상에서는 쿼리 캐시가 제거되었지만, **InnoDB 버퍼 풀 캐시**가 있어서 정확한 측정을 위해 캐시를 무효화해야 합니다.

#### 각 시나리오 테스트 전에 실행:
```sql
-- 테이블 캐시 플러시
FLUSH TABLES product;

-- 테이블 통계 정보 업데이트
ANALYZE TABLE product;
```

#### 주의사항:
- `FLUSH TABLES`는 테이블 락을 걸 수 있으므로 운영 환경에서는 주의
- 각 시나리오 테스트 전에 캐시를 무효화하면 더 정확한 측정이 가능
- 여러 번 실행하여 평균값을 계산하는 것이 좋음

### 2. 여러 번 실행
- 각 시나리오별로 여러 번 실행하여 평균값을 계산하는 것이 정확합니다.
- 첫 1-2회는 워밍업으로 제외하는 것을 권장합니다.

### 3. 테스트 브랜드 ID
- 실제 데이터에 존재하는 브랜드 ID를 사용하세요.
- 브랜드별 상품 수가 충분한 브랜드를 선택하세요 (최소 1,000개 이상 권장).

### 4. 인덱스 생성 시간
- 대량 데이터(100,000개 이상)에서는 인덱스 생성에 시간이 걸릴 수 있습니다.
- 인덱스 생성 중에는 다른 작업을 피하세요.

## 결과 문서화

테스트 결과를 다음 형식으로 문서화하세요:

```markdown
## 테스트 환경
- 데이터량: 100,000개 상품
- 브랜드 수: 100개
- 테스트 브랜드 ID: 1
- 테스트 브랜드 상품 수: 1,000개

## 결과

### 시나리오 1: 인덱스 없음
- type: ALL
- key: NULL
- rows: 100,000
- Extra: Using where; Using filesort
- 실행 시간: 평균 500ms

### 시나리오 2: 단일 인덱스
- type: ref
- key: idx_product_brand_id
- rows: 1,000
- Extra: Using filesort
- 실행 시간: 평균 100ms
- 개선율: 80% 개선

### 시나리오 3: 복합 인덱스
- type: ref
- key: idx_product_brand_created
- rows: 20
- Extra: Using index condition
- 실행 시간: 평균 10ms
- 개선율: 98% 개선 (인덱스 없음 대비), 90% 개선 (단일 인덱스 대비)

## 결론
복합 인덱스 (ref_brand_id, created_at)를 사용하면 
브랜드 필터 + 최신순 정렬 쿼리의 성능이 크게 개선됩니다.
```

## 참고 파일

- SQL 스크립트: `apps/commerce-api/docs/performance/index-comparison-test.sql`
- 테스트 계획: `apps/commerce-api/docs/performance/index-comparison-test-plan.md`

