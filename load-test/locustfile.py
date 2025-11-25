"""
Commerce API 부하 테스트 스크립트
Locust를 사용하여 Commerce API의 성능을 테스트합니다.

사용 방법:
    # 웹 UI로 실행
    locust -f locustfile.py --host=http://localhost:8080

    # 헤드리스 모드로 실행
    locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m

    # 특정 태그만 실행
    locust -f locustfile.py --host=http://localhost:8080 --tags catalog
"""

import random
from locust import HttpUser, task, between, tag
from typing import List


class CommerceApiUser(HttpUser):
    """
    Commerce API 부하 테스트를 위한 사용자 클래스
    """
    wait_time = between(1, 3)  # 요청 간 대기 시간 (1~3초)
    
    def on_start(self):
        """
        사용자 세션 시작 시 초기화
        """
        # 테스트용 사용자 ID 범위 (실제 데이터에 맞게 조정 필요)
        self.user_id = str(random.randint(1, 10000))
        self.headers = {"X-USER-ID": self.user_id}
        
        # 세션 동안 사용할 상품 ID 목록 (캐싱)
        self.product_ids: List[int] = []
        self.brand_ids: List[int] = []
        self.order_ids: List[int] = []
        
        # 초기 데이터 로드
        self._load_initial_data()
    
    def _load_initial_data(self):
        """
        초기 데이터 로드 (상품 ID, 브랜드 ID 등)
        """
        # 상품 목록 조회하여 상품 ID 수집
        with self.client.get("/api/v1/products?size=50", catch_response=True) as response:
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
    
    # ==================== Catalog API ====================
    
    @tag("catalog", "read")
    @task(10)
    def get_products(self):
        """
        상품 목록 조회 (가장 빈번한 조회)
        """
        sort_options = ["latest", "price_asc", "likes_desc"]
        sort = random.choice(sort_options)
        page = random.randint(0, 10)
        size = random.choice([20, 40, 60])
        
        self.client.get(
            f"/api/v1/products?sort={sort}&page={page}&size={size}",
            name="/api/v1/products [목록 조회]"
        )
    
    @tag("catalog", "read")
    @task(8)
    def get_products_by_brand(self):
        """
        브랜드별 상품 목록 조회
        """
        if self.brand_ids:
            brand_id = random.choice(self.brand_ids)
        else:
            brand_id = random.randint(1, 100)
        
        page = random.randint(0, 5)
        self.client.get(
            f"/api/v1/products?brandId={brand_id}&page={page}",
            name="/api/v1/products [브랜드별 조회]"
        )
    
    @tag("catalog", "read")
    @task(10)
    def get_product_detail(self):
        """
        상품 상세 조회
        """
        if self.product_ids:
            product_id = random.choice(self.product_ids)
        else:
            product_id = random.randint(1, 100000)
        
        self.client.get(
            f"/api/v1/products/{product_id}",
            name="/api/v1/products/{productId} [상세 조회]"
        )
    
    @tag("catalog", "read")
    @task(3)
    def get_brand(self):
        """
        브랜드 정보 조회
        """
        brand_id = random.randint(1, 100)
        self.client.get(
            f"/api/v1/brands/{brand_id}",
            name="/api/v1/brands/{brandId}"
        )
    
    # ==================== User Info API ====================
    
    @tag("user", "read")
    @task(5)
    def get_my_info(self):
        """
        내 정보 조회
        """
        self.client.get(
            "/api/v1/me",
            headers=self.headers,
            name="/api/v1/me [내 정보 조회]"
        )
    
    # ==================== Point Wallet API ====================
    
    @tag("point", "read")
    @task(5)
    def get_my_points(self):
        """
        내 포인트 조회
        """
        self.client.get(
            "/api/v1/me/points",
            headers=self.headers,
            name="/api/v1/me/points [포인트 조회]"
        )
    
    @tag("point", "write")
    @task(2)
    def charge_points(self):
        """
        포인트 충전
        """
        amount = random.choice([1000, 5000, 10000, 50000, 100000])
        payload = {"amount": amount}
        
        self.client.post(
            "/api/v1/me/points/charge",
            json=payload,
            headers=self.headers,
            name="/api/v1/me/points/charge [포인트 충전]"
        )
    
    # ==================== Like API ====================
    
    @tag("like", "read")
    @task(4)
    def get_liked_products(self):
        """
        좋아요한 상품 목록 조회
        """
        self.client.get(
            "/api/v1/like/products",
            headers=self.headers,
            name="/api/v1/like/products [좋아요 목록]"
        )
    
    @tag("like", "write")
    @task(3)
    def add_like(self):
        """
        좋아요 추가
        """
        if self.product_ids:
            product_id = random.choice(self.product_ids)
        else:
            product_id = random.randint(1, 100000)
        
        self.client.post(
            f"/api/v1/like/products/{product_id}",
            headers=self.headers,
            name="/api/v1/like/products/{productId} [좋아요 추가]"
        )
    
    @tag("like", "write")
    @task(2)
    def remove_like(self):
        """
        좋아요 삭제
        """
        if self.product_ids:
            product_id = random.choice(self.product_ids)
        else:
            product_id = random.randint(1, 100000)
        
        self.client.delete(
            f"/api/v1/like/products/{product_id}",
            headers=self.headers,
            name="/api/v1/like/products/{productId} [좋아요 삭제]"
        )
    
    # ==================== Order API ====================
    
    @tag("order", "read")
    @task(4)
    def get_orders(self):
        """
        주문 목록 조회
        """
        self.client.get(
            "/api/v1/orders",
            headers=self.headers,
            name="/api/v1/orders [주문 목록]"
        )
    
    @tag("order", "read")
    @task(2)
    def get_order_detail(self):
        """
        주문 상세 조회
        """
        if self.order_ids:
            order_id = random.choice(self.order_ids)
        else:
            order_id = random.randint(1, 50000)
        
        self.client.get(
            f"/api/v1/orders/{order_id}",
            headers=self.headers,
            name="/api/v1/orders/{orderId} [주문 상세]"
        )
    
    @tag("order", "write")
    @task(1)
    def create_order(self):
        """
        주문 생성
        """
        if not self.product_ids:
            return
        
        # 1~3개의 상품을 랜덤하게 선택하여 주문
        num_items = random.randint(1, 3)
        selected_products = random.sample(self.product_ids, min(num_items, len(self.product_ids)))
        
        items = [
            {
                "productId": product_id,
                "quantity": random.randint(1, 5)
            }
            for product_id in selected_products
        ]
        
        payload = {"items": items}
        
        with self.client.post(
            "/api/v1/orders",
            json=payload,
            headers=self.headers,
            name="/api/v1/orders [주문 생성]",
            catch_response=True
        ) as response:
            if response.status_code == 200:
                try:
                    data = response.json()
                    if "data" in data and "orderId" in data["data"]:
                        self.order_ids.append(data["data"]["orderId"])
                except:
                    pass
                response.success()
            else:
                response.failure(f"주문 생성 실패: {response.status_code}")


# ==================== 추가 설정 ====================

class WebsiteUser(CommerceApiUser):
    """
    웹사이트 사용자 시나리오 (읽기 중심)
    """
    wait_time = between(2, 5)
    
    @task
    def browse_products(self):
        """상품 둘러보기"""
        self.get_products()
        self.get_product_detail()
    
    @task
    def view_brand(self):
        """브랜드 보기"""
        self.get_brand()
        self.get_products_by_brand()


class MobileAppUser(CommerceApiUser):
    """
    모바일 앱 사용자 시나리오 (좋아요, 주문 포함)
    """
    wait_time = between(1, 2)
    
    @task
    def shopping_flow(self):
        """쇼핑 플로우"""
        self.get_products()
        self.get_product_detail()
        if random.random() < 0.3:  # 30% 확률로 좋아요
            self.add_like()
        if random.random() < 0.1:  # 10% 확률로 주문
            self.create_order()

