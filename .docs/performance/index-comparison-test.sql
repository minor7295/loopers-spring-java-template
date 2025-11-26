-- ============================================
-- 인덱스 성능 비교 테스트 SQL 스크립트
-- ============================================
-- 목적: 브랜드 필터 + 최신순 정렬 쿼리의 성능을 
--       3가지 인덱스 시나리오별로 비교
--
-- 시나리오:
--   1. 인덱스 없음 (PK 제외)
--   2. 단일 인덱스 (ref_brand_id)
--   3. 복합 인덱스 (ref_brand_id, created_at)
-- ============================================

-- ============================================
-- 1단계: 테스트 데이터 준비
-- ============================================
-- (데이터 시딩이 완료된 상태라고 가정)
-- 데이터 확인
SELECT COUNT(*) as total_products FROM product;
SELECT COUNT(DISTINCT ref_brand_id) as brand_count FROM product;
SELECT ref_brand_id, COUNT(*) as product_count 
FROM product 
GROUP BY ref_brand_id 
LIMIT 10;

-- 테스트용 브랜드 ID 선택 (예: 첫 번째 브랜드)
SET @test_brand_id = (SELECT ref_brand_id FROM product LIMIT 1);
SELECT @test_brand_id as test_brand_id;

-- ============================================
-- 2단계: 시나리오 1 - 인덱스 없음
-- ============================================
-- 모든 인덱스 제거 (PK는 제거 불가)
DROP INDEX IF EXISTS idx_product_brand_id ON product;
DROP INDEX IF EXISTS idx_product_brand_created ON product;
DROP INDEX IF EXISTS idx_product_brand_likes ON product;
DROP INDEX IF EXISTS idx_product_brand_price ON product;
DROP INDEX IF EXISTS idx_product_likes ON product;
DROP INDEX IF EXISTS idx_product_price ON product;

-- 현재 인덱스 상태 확인
SHOW INDEX FROM product;

-- 캐시 무효화 (정확한 측정을 위해)
-- 주의: FLUSH TABLES는 테이블 락을 걸 수 있으므로 운영 환경에서는 주의
FLUSH TABLES product;
-- 테이블 통계 정보 업데이트
ANALYZE TABLE product;

-- EXPLAIN 분석
EXPLAIN 
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = @test_brand_id 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;

-- 성능 측정 (10회 실행 후 평균)
-- 주의: 각 실행 전에 쿼리 캐시를 클리어하는 것이 좋습니다
SET @start_time = NOW(3);
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = @test_brand_id 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;
SELECT TIMESTAMPDIFF(MICROSECOND, @start_time, NOW(3)) / 1000 as execution_time_ms;

-- ============================================
-- 3단계: 시나리오 2 - 단일 인덱스 (ref_brand_id)
-- ============================================
-- 단일 인덱스 생성
CREATE INDEX idx_product_brand_id ON product(ref_brand_id);

-- 인덱스 생성 확인
SHOW INDEX FROM product WHERE Key_name = 'idx_product_brand_id';

-- 캐시 무효화 및 통계 정보 업데이트
FLUSH TABLES product;
ANALYZE TABLE product;

-- EXPLAIN 분석
EXPLAIN 
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = @test_brand_id 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;

-- 성능 측정
SET @start_time = NOW(3);
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = @test_brand_id 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;
SELECT TIMESTAMPDIFF(MICROSECOND, @start_time, NOW(3)) / 1000 as execution_time_ms;

-- ============================================
-- 4단계: 시나리오 3 - 복합 인덱스 (ref_brand_id, created_at)
-- ============================================
-- 단일 인덱스 제거 후 복합 인덱스 생성
DROP INDEX idx_product_brand_id ON product;

-- 복합 인덱스 생성
CREATE INDEX idx_product_brand_created ON product(ref_brand_id, created_at);

-- 인덱스 생성 확인
SHOW INDEX FROM product WHERE Key_name = 'idx_product_brand_created';

-- 캐시 무효화 및 통계 정보 업데이트
FLUSH TABLES product;
ANALYZE TABLE product;

-- EXPLAIN 분석
EXPLAIN 
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = @test_brand_id 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;

-- 성능 측정
SET @start_time = NOW(3);
SELECT p.* 
FROM product p 
WHERE p.ref_brand_id = @test_brand_id 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;
SELECT TIMESTAMPDIFF(MICROSECOND, @start_time, NOW(3)) / 1000 as execution_time_ms;

-- ============================================
-- 5단계: 결과 비교 및 정리
-- ============================================
-- 최종 인덱스 상태 확인
SHOW INDEX FROM product;

-- ============================================
-- 참고: 캐시 무효화 방법
-- ============================================
-- MySQL 8.0 이상에서는 쿼리 캐시가 제거되었지만,
-- InnoDB 버퍼 풀 캐시가 있어서 정확한 측정을 위해 캐시를 무효화해야 합니다.
--
-- 방법 1: 테이블 캐시 플러시 (권장)
-- FLUSH TABLES product;
--
-- 방법 2: 테이블 통계 정보 업데이트
-- ANALYZE TABLE product;
--
-- 방법 3: 버퍼 풀 전체 플러시 (주의: 성능에 영향)
-- RESET QUERY CACHE;  -- MySQL 8.0에서는 사용 불가
--
-- 주의사항:
-- - FLUSH TABLES는 테이블 락을 걸 수 있으므로 운영 환경에서는 주의
-- - 각 시나리오 테스트 전에 캐시를 무효화하면 더 정확한 측정이 가능합니다
-- - 여러 번 실행하여 평균값을 계산하는 것이 좋습니다

-- ============================================
-- 참고: 여러 번 실행하여 평균 측정
-- ============================================
-- 각 시나리오별로 위의 성능 측정 쿼리를 10회 정도 실행하고
-- 평균값을 계산하는 것을 권장합니다.
--
-- 예시:
-- 시나리오 1 (인덱스 없음): 500ms, 520ms, 480ms, ... → 평균 ~500ms
-- 시나리오 2 (단일 인덱스): 100ms, 95ms, 105ms, ... → 평균 ~100ms
-- 시나리오 3 (복합 인덱스): 10ms, 12ms, 8ms, ... → 평균 ~10ms
-- ============================================

