--  docker exec -it docker-mysql-1 bash
--  mysql --user root --passwor
--  use loopers;


-- 상품 조회 API 테스트용 예제 데이터 삽입 SQL
-- 브랜드와 상품 데이터를 생성합니다.

-- ============================================
-- 1. 브랜드 데이터 삽입
-- ============================================
INSERT INTO brand (name, created_at, updated_at, deleted_at) VALUES
('나이키', NOW(), NOW(), NULL),
('아디다스', NOW(), NOW(), NULL),
('퓨마', NOW(), NOW(), NULL),
('뉴발란스', NOW(), NOW(), NULL),
('컨버스', NOW(), NOW(), NULL);

-- ============================================
-- 2. 상품 데이터 삽입
-- ============================================
-- 나이키 상품들 (brand_id = 1)
INSERT INTO product (name, price, stock, brand_id, created_at, updated_at, deleted_at) VALUES
('나이키 에어맥스 90', 129000, 50, 1, NOW(), NOW(), NULL),
('나이키 덩크 로우', 99000, 30, 1, NOW(), NOW(), NULL),
('나이키 에어포스 1', 109000, 40, 1, NOW(), NOW(), NULL),
('나이키 코르테즈', 89000, 25, 1, NOW(), NOW(), NULL),
('나이키 블레이저 미드', 119000, 35, 1, NOW(), NOW(), NULL);

-- 아디다스 상품들 (brand_id = 2)
INSERT INTO product (name, price, stock, brand_id, created_at, updated_at, deleted_at) VALUES
('아디다스 스탠스미스', 99000, 45, 2, NOW(), NOW(), NULL),
('아디다스 슈퍼스타', 109000, 50, 2, NOW(), NOW(), NULL),
('아디다스 가젤', 89000, 30, 2, NOW(), NOW(), NULL),
('아디다스 울트라부스트', 199000, 20, 2, NOW(), NOW(), NULL),
('아디다스 삼바', 79000, 40, 2, NOW(), NOW(), NULL);

-- 퓨마 상품들 (brand_id = 3)
INSERT INTO product (name, price, stock, brand_id, created_at, updated_at, deleted_at) VALUES
('퓨마 스웨이드 클래식', 69000, 35, 3, NOW(), NOW(), NULL),
('퓨마 RS-X', 99000, 25, 3, NOW(), NOW(), NULL),
('퓨마 썬더', 89000, 30, 3, NOW(), NOW(), NULL),
('퓨마 벨벳', 79000, 20, 3, NOW(), NOW(), NULL);

-- 뉴발란스 상품들 (brand_id = 4)
INSERT INTO product (name, price, stock, brand_id, created_at, updated_at, deleted_at) VALUES
('뉴발란스 574', 99000, 40, 4, NOW(), NOW(), NULL),
('뉴발란스 327', 109000, 35, 4, NOW(), NOW(), NULL),
('뉴발란스 550', 119000, 30, 4, NOW(), NOW(), NULL),
('뉴발란스 993', 199000, 15, 4, NOW(), NOW(), NULL);

-- 컨버스 상품들 (brand_id = 5)
INSERT INTO product (name, price, stock, brand_id, created_at, updated_at, deleted_at) VALUES
('컨버스 척 테일러 올스타', 69000, 50, 5, NOW(), NOW(), NULL),
('컨버스 원스타', 79000, 45, 5, NOW(), NOW(), NULL),
('컨버스 잭퍼셀', 89000, 40, 5, NOW(), NOW(), NULL);

-- ============================================
-- 3. 좋아요 데이터 삽입 (선택사항)
-- ============================================
-- 좋아요 테스트를 위한 샘플 데이터
-- 주의: user 테이블에 해당 사용자들이 존재해야 합니다.
-- INSERT INTO `like` (ref_user_id, ref_product_id, created_at, updated_at, deleted_at) VALUES
-- (1, 1, NOW(), NOW(), NULL),  -- 사용자 1이 상품 1 좋아요
-- (1, 2, NOW(), NOW(), NULL),  -- 사용자 1이 상품 2 좋아요
-- (1, 5, NOW(), NOW(), NULL),  -- 사용자 1이 상품 5 좋아요
-- (2, 1, NOW(), NOW(), NULL),  -- 사용자 2가 상품 1 좋아요
-- (2, 3, NOW(), NOW(), NULL),  -- 사용자 2가 상품 3 좋아요
-- (2, 4, NOW(), NOW(), NULL),  -- 사용자 2가 상품 4 좋아요
-- (3, 1, NOW(), NOW(), NULL),  -- 사용자 3이 상품 1 좋아요
-- (3, 2, NOW(), NOW(), NULL),  -- 사용자 3이 상품 2 좋아요
-- (3, 3, NOW(), NOW(), NULL);  -- 사용자 3이 상품 3 좋아요

-- ============================================
-- 참고사항
-- ============================================
-- 1. 브랜드 ID는 자동 증가되므로, 실제 삽입된 ID를 확인한 후 상품 데이터의 brand_id를 조정해야 할 수 있습니다.
-- 2. 좋아요 데이터는 user 테이블에 사용자가 존재할 때만 사용하세요.
-- 3. 테스트 시 다양한 시나리오를 위해:
--    - 가격대가 다양한 상품들 (69,000원 ~ 199,000원)
--    - 재고가 다양한 상품들 (15개 ~ 50개)
--    - 브랜드별로 다른 수량의 상품들
-- 4. 정렬 테스트:
--    - latest: created_at 기준 (최신순)
--    - price_asc: price 기준 (가격 낮은순)
--    - likes_desc: 좋아요 수 기준 (좋아요 많은순) - 좋아요 데이터 필요

