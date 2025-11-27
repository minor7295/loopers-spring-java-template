-- ============================================
-- 상품 목록 조회 성능 개선 인덱스 생성 스크립트
-- ============================================
-- 실행 방법:
--   mysql -u [username] -p [database_name] < create-product-indexes.sql
--   또는 MySQL 클라이언트에서 직접 실행
--
-- 실행 전 확인:
--   1. product 테이블이 존재하는지 확인
--   2. 기존 인덱스와 충돌하지 않는지 확인
-- ============================================

-- 브랜드 필터 + 좋아요 순 정렬 최적화
-- 사용 쿼리: WHERE ref_brand_id = ? ORDER BY like_count DESC
CREATE INDEX IF NOT EXISTS idx_product_brand_likes 
ON product(ref_brand_id, like_count);

-- 브랜드 필터 + 최신순 정렬 최적화
-- 사용 쿼리: WHERE ref_brand_id = ? ORDER BY created_at DESC
CREATE INDEX IF NOT EXISTS idx_product_brand_created 
ON product(ref_brand_id, created_at);

-- 브랜드 필터 + 가격순 정렬 최적화
-- 사용 쿼리: WHERE ref_brand_id = ? ORDER BY price ASC
CREATE INDEX IF NOT EXISTS idx_product_brand_price 
ON product(ref_brand_id, price);

-- 전체 조회 + 좋아요 순 정렬 최적화
-- 사용 쿼리: ORDER BY like_count DESC
CREATE INDEX IF NOT EXISTS idx_product_likes 
ON product(like_count);

-- 전체 조회 + 가격순 정렬 최적화
-- 사용 쿼리: ORDER BY price ASC
CREATE INDEX IF NOT EXISTS idx_product_price 
ON product(price);

-- 전체 조회 + 최신순 정렬 최적화
-- 사용 쿼리: ORDER BY created_at DESC
CREATE INDEX IF NOT EXISTS idx_product_created 
ON product(created_at);

-- ============================================
-- 인덱스 생성 확인
-- ============================================
-- SHOW INDEX FROM product;
--
-- 예상 결과:
-- - idx_product_brand_likes
-- - idx_product_brand_created
-- - idx_product_brand_price
-- - idx_product_likes
-- - idx_product_price
-- - idx_product_created
-- ============================================

