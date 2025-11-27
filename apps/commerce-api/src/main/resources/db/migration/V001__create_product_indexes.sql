-- 상품 목록 조회 성능 개선을 위한 인덱스 생성
-- 브랜드 필터 + 좋아요 순 정렬 최적화
CREATE INDEX IF NOT EXISTS idx_product_brand_likes ON product(ref_brand_id, like_count);

-- 브랜드 필터 + 최신순 정렬 최적화
CREATE INDEX IF NOT EXISTS idx_product_brand_created ON product(ref_brand_id, created_at);

-- 브랜드 필터 + 가격순 정렬 최적화
CREATE INDEX IF NOT EXISTS idx_product_brand_price ON product(ref_brand_id, price);

-- 전체 조회 + 좋아요 순 정렬 최적화
CREATE INDEX IF NOT EXISTS idx_product_likes ON product(like_count);

-- 전체 조회 + 가격순 정렬 최적화
CREATE INDEX IF NOT EXISTS idx_product_price ON product(price);

-- 전체 조회 + 최신순 정렬 최적화
CREATE INDEX IF NOT EXISTS idx_product_created ON product(created_at);

