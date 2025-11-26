"""
Commerce API 상품 조회 부하 테스트 스크립트
인덱스와 캐시의 성능 차이를 확인하기 위한 테스트입니다.

사용 방법:
    # 웹 UI로 실행
    locust -f locustfile.py --host=http://localhost:8080

    # 헤드리스 모드로 실행
    locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m

    # 인덱스 테스트만 실행
    locust -f locustfile.py --host=http://localhost:8080 --tags index

    # 캐시 테스트만 실행
    locust -f locustfile.py --host=http://localhost:8080 --tags cache
"""

import random
from locust import HttpUser, task, between, tag
from typing import List


class ProductQueryUser(HttpUser):
    """
    상품 조회 부하 테스트를 위한 기본 사용자 클래스
    """
    wait_time = between(1, 2)  # 요청 간 대기 시간 (1~2초)
    
    def on_start(self):
        """
        사용자 세션 시작 시 초기화
        """
        # 테스트용 데이터 범위 (실제 데이터에 맞게 조정 필요)
        self.product_ids: List[int] = []
        self.brand_ids: List[int] = list(range(1, 101))  # 브랜드 ID 1~100
        
        # 초기 데이터 로드
        self._load_initial_data()
    
    def _load_initial_data(self):
        """
        초기 데이터 로드 (상품 ID 수집)
        """
        # 상품 목록 조회하여 상품 ID 수집
        with self.client.get("/api/v1/products?size=100", catch_response=True) as response:
            if response.status_code == 200:
                try:
                    data = response.json()
                    if "data" in data and "products" in data["data"]:
                        self.product_ids = [
                            product["productId"] 
                            for product in data["data"]["products"]
                        ]
                except:
                    pass
    
    # ==================== 인덱스 성능 테스트 ====================
    # 인덱스 사용 여부에 따른 성능 차이를 확인하기 위한 테스트
    
    @tag("index", "list")
    @task(5)
    def get_products_all_latest(self):
        """
        전체 조회 + 최신순 정렬 (인덱스: idx_product_created 또는 없음)
        """
        page = random.randint(0, 10)
        self.client.get(
            f"/api/v1/products?sort=latest&page={page}&size=20",
            name="/api/v1/products [전체-최신순]"
        )
    
    @tag("index", "list")
    @task(5)
    def get_products_all_likes_desc(self):
        """
        전체 조회 + 좋아요순 정렬 (인덱스: idx_product_likes)
        """
        page = random.randint(0, 10)
        self.client.get(
            f"/api/v1/products?sort=likes_desc&page={page}&size=20",
            name="/api/v1/products [전체-좋아요순]"
        )
    
    @tag("index", "list")
    @task(5)
    def get_products_all_price_asc(self):
        """
        전체 조회 + 가격순 정렬 (인덱스: idx_product_price)
        """
        page = random.randint(0, 10)
        self.client.get(
            f"/api/v1/products?sort=price_asc&page={page}&size=20",
            name="/api/v1/products [전체-가격순]"
        )
    
    @tag("index", "list")
    @task(5)
    def get_products_by_brand_latest(self):
        """
        브랜드 필터 + 최신순 정렬 (인덱스: idx_product_brand_created)
        """
        brand_id = random.choice(self.brand_ids)
        page = random.randint(0, 5)
        self.client.get(
            f"/api/v1/products?brandId={brand_id}&sort=latest&page={page}&size=20",
            name="/api/v1/products [브랜드-최신순]"
        )
    
    @tag("index", "list")
    @task(5)
    def get_products_by_brand_likes_desc(self):
        """
        브랜드 필터 + 좋아요순 정렬 (인덱스: idx_product_brand_likes)
        """
        brand_id = random.choice(self.brand_ids)
        page = random.randint(0, 5)
        self.client.get(
            f"/api/v1/products?brandId={brand_id}&sort=likes_desc&page={page}&size=20",
            name="/api/v1/products [브랜드-좋아요순]"
        )
    
    @tag("index", "list")
    @task(5)
    def get_products_by_brand_price_asc(self):
        """
        브랜드 필터 + 가격순 정렬 (인덱스: idx_product_brand_price)
        """
        brand_id = random.choice(self.brand_ids)
        page = random.randint(0, 5)
        self.client.get(
            f"/api/v1/products?brandId={brand_id}&sort=price_asc&page={page}&size=20",
            name="/api/v1/products [브랜드-가격순]"
        )
    
    # ==================== 캐시 성능 테스트 ====================
    # 캐시 히트/미스에 따른 성능 차이를 확인하기 위한 테스트
    
    @tag("cache", "list", "cache_hit")
    @task(10)
    def get_products_first_page_cached(self):
        """
        첫 페이지 조회 (캐시 히트) - page=0만 캐시됨
        """
        sort_options = ["latest", "price_asc", "likes_desc"]
        sort = random.choice(sort_options)
        # 첫 페이지만 캐시되므로 page=0으로 고정
        self.client.get(
            f"/api/v1/products?sort={sort}&page=0&size=20",
            name="/api/v1/products [첫페이지-캐시히트]"
        )
    
    @tag("cache", "list", "cache_miss")
    @task(5)
    def get_products_other_pages_not_cached(self):
        """
        다른 페이지 조회 (캐시 미스) - page > 0은 캐시되지 않음
        """
        sort_options = ["latest", "price_asc", "likes_desc"]
        sort = random.choice(sort_options)
        page = random.randint(1, 10)  # 첫 페이지 제외
        self.client.get(
            f"/api/v1/products?sort={sort}&page={page}&size=20",
            name="/api/v1/products [다른페이지-캐시미스]"
        )
    
    @tag("cache", "list", "cache_hit")
    @task(5)
    def get_products_by_brand_first_page_cached(self):
        """
        브랜드별 첫 페이지 조회 (캐시 히트)
        """
        brand_id = random.choice(self.brand_ids)
        sort_options = ["latest", "price_asc", "likes_desc"]
        sort = random.choice(sort_options)
        # 첫 페이지만 캐시되므로 page=0으로 고정
        self.client.get(
            f"/api/v1/products?brandId={brand_id}&sort={sort}&page=0&size=20",
            name="/api/v1/products [브랜드-첫페이지-캐시히트]"
        )
    
    @tag("cache", "detail", "cache_hit")
    @task(10)
    def get_product_detail_cached(self):
        """
        상품 상세 조회 (캐시 히트) - 모든 상품 상세는 캐시됨
        """
        if self.product_ids:
            # 같은 상품을 반복 조회하여 캐시 히트율 높임
            product_id = random.choice(self.product_ids[:50])  # 상위 50개만 반복 조회
        else:
            product_id = random.randint(1, 1000)  # 캐시된 상품 범위
        
        self.client.get(
            f"/api/v1/products/{product_id}",
            name="/api/v1/products/{productId} [상세-캐시히트]"
        )
    
    @tag("cache", "detail", "cache_miss")
    @task(3)
    def get_product_detail_not_cached(self):
        """
        상품 상세 조회 (캐시 미스) - 다양한 상품 조회로 캐시 미스 유도
        """
        # 넓은 범위의 상품 ID로 캐시 미스 유도
        product_id = random.randint(1000, 100000)
        self.client.get(
            f"/api/v1/products/{product_id}",
            name="/api/v1/products/{productId} [상세-캐시미스]"
        )
    
    # ==================== 페이지네이션 테스트 ====================
    # 페이지 깊이에 따른 성능 차이 확인
    
    @tag("pagination", "list")
    @task(3)
    def get_products_deep_pagination(self):
        """
        깊은 페이지 조회 (OFFSET이 큰 경우 성능 저하 가능)
        """
        page = random.randint(20, 50)  # 깊은 페이지
        sort = random.choice(["latest", "price_asc", "likes_desc"])
        self.client.get(
            f"/api/v1/products?sort={sort}&page={page}&size=20",
            name="/api/v1/products [깊은페이지]"
        )


# ==================== 특화된 테스트 시나리오 ====================

class IndexPerformanceUser(ProductQueryUser):
    """
    인덱스 성능 테스트 전용 사용자
    다양한 정렬 옵션과 브랜드 필터 조합을 테스트
    """
    wait_time = between(0.5, 1)
    
    @tag("index")
    @task
    def test_index_scenarios(self):
        """인덱스 시나리오 테스트"""
        self.get_products_all_latest()
        self.get_products_all_likes_desc()
        self.get_products_all_price_asc()
        self.get_products_by_brand_latest()
        self.get_products_by_brand_likes_desc()
        self.get_products_by_brand_price_asc()


class CachePerformanceUser(ProductQueryUser):
    """
    캐시 성능 테스트 전용 사용자
    캐시 히트/미스 시나리오를 집중 테스트
    """
    wait_time = between(0.5, 1)
    
    @tag("cache")
    @task
    def test_cache_scenarios(self):
        """캐시 시나리오 테스트"""
        # 캐시 히트 시나리오 (높은 빈도)
        self.get_products_first_page_cached()
        self.get_product_detail_cached()
        self.get_products_by_brand_first_page_cached()
        
        # 캐시 미스 시나리오 (낮은 빈도)
        if random.random() < 0.3:  # 30% 확률
            self.get_products_other_pages_not_cached()
        if random.random() < 0.2:  # 20% 확률
            self.get_product_detail_not_cached()


class MixedLoadUser(ProductQueryUser):
    """
    혼합 부하 테스트 사용자
    실제 사용자 패턴을 시뮬레이션
    """
    wait_time = between(1, 3)
    
    @task
    def realistic_user_behavior(self):
        """실제 사용자 행동 패턴"""
        # 대부분 첫 페이지 조회 (캐시 히트)
        if random.random() < 0.7:  # 70% 확률
            self.get_products_first_page_cached()
        else:  # 30% 확률
            self.get_products_other_pages_not_cached()
        
        # 상품 상세 조회
        if random.random() < 0.5:  # 50% 확률
            self.get_product_detail_cached()
        
        # 브랜드 필터 사용 (20% 확률)
        if random.random() < 0.2:  # 20% 확률
            self.get_products_by_brand_first_page_cached()
