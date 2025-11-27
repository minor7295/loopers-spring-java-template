"""
Commerce API 상품 조회 부하 테스트 스크립트
인덱스와 캐시의 성능 차이를 확인하기 위한 테스트입니다.

사용 방법:
    # 웹 UI로 실행
    locust -f locustfile.py --host=http://localhost:8080

    # 헤드리스 모드로 실행
    locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m

    # 상품 관련 테스트만 실행
    locust -f locustfile.py --host=http://localhost:8080 --tags catalog

    # 주문 관련 테스트만 실행
    locust -f locustfile.py --host=http://localhost:8080 --tags purchasing

    # 특정 사용자 클래스만 실행
    locust -f locustfile.py --host=http://localhost:8080 --class LowInvolvementUser
"""
from typing import List
import random

from locust import (
    HttpUser,
    LoadTestShape,
    between,
    events,
    tag,
    task,
)
import requests

# 테스트 시작 시 한 번만 로드되는 상품 ID 목록
SHARED_PRODUCT_IDS: List[int] = []

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    """
    테스트 시작 시 한 번만 실행되어 첫 페이지에서 상품 ID를 수집합니다.
    """
    global SHARED_PRODUCT_IDS
    if not SHARED_PRODUCT_IDS:
        host = environment.host
        try:
            response = requests.get(f"{host}/api/v1/products?page=0", timeout=5)
            if response.status_code == 200:
                data = response.json()
                if "data" in data and "products" in data["data"]:
                    SHARED_PRODUCT_IDS = [
                        product["productId"] 
                        for product in data["data"]["products"]
                    ]
                    print(f"[TEST START] Loaded {len(SHARED_PRODUCT_IDS)} product IDs")
        except Exception as e:
            print(f"[TEST START] Failed to load products: {e}")

# ================================
#  Low Involvement (저관여 고객)
#  - 첫페이지 의존, 정렬 거의 사용X, 상세 전환 낮음
# ================================
class LowInvolvementUser(HttpUser):
    wait_time = between(1, 3)
    weight = 80  # 트래픽 비중

    @tag("catalog")
    @task(10)
    def product_list_first_page(self):
        self.client.get("/api/v1/products")

    @tag("catalog")
    @task(2)
    def product_list_second_page(self):
        self.client.get("/api/v1/products?page=1")

    @tag("catalog")
    @task(1)
    def view_detail(self):
        """
        첫 페이지에 있는 상품 중 하나를 랜덤하게 조회
        """
        product_id = random.choice(SHARED_PRODUCT_IDS)
        self.client.get(f"/api/v1/products/{product_id}")


# ================================
#  High Involvement (고관여 고객)
#  - 여러 페이지를 순차적으로 탐색
#  - 페이지가 깊어질수록 탐색하는 사용자 수 감소
# ================================
class HighInvolvementUser(HttpUser):
    wait_time = between(1, 2)
    weight = 20  # 트래픽 비중

    def _get_max_page_depth(self):
        """
        페이지 깊이를 결정 (깊을수록 낮은 확률)
        지수 분포를 사용하여 대부분의 사용자는 앞 페이지만 탐색
        """
        rand = random.random()
        if rand < 0.50:
            return 3  # 50% 확률로 1페이지만
        elif rand < 0.80:
            return 4  # 30% 확률로 2페이지까지
        elif rand < 0.95:
            return 5  # 15% 확률로 3페이지까지
        elif rand < 0.99:
            return 6  # 4% 확률로 4페이지까지
        else:
            return 7

    @tag("catalog")
    @task(10)
    def sequential_page_browsing(self):
        """
        순차적으로 여러 페이지를 탐색
        페이지가 깊어질수록 탐색하는 사용자가 적어짐
        """
        sort_options = ["latest", "price_asc", "likes_desc"]
        max_depth = self._get_max_page_depth()

        for sort in sort_options:
            # 순차적으로 페이지 탐색 (1페이지부터 max_depth까지)
            for page in range(0, max_depth + 1):
                self.client.get(f"/api/v1/products?sort={sort}&page={page}")

    @tag("catalog")
    @task(3)
    def view_detail_from_pages(self):
        """
        상품 상세 조회
        """
        product_id = random.randint(0, 100000)
        self.client.get(f"/api/v1/products/{product_id}")


class BlackFridayMillionShape(LoadTestShape):
    """
    블랙프라이데이 알림 이후
    100만 사용자가 몰리는 트래픽 패턴 시뮬레이션
    """

    stages = [
        {"duration": 10, "users": 1_000_000, "spawn_rate": 200_000}, # 0~10초 사이 100만명 돌입 (극스파이크)
        {"duration": 120, "users": 1_000_000, "spawn_rate": 1},     # 2분 유지 → 서버 버티나?
        {"duration": 300, "users": 600_000,  "spawn_rate": 50_000}, # 일부 구매 종료로 40% 감소
        {"duration": 480, "users": 300_000,  "spawn_rate": 20_000}, # 탐색만 하는 사용자 감소
        {"duration": 600, "users": 50_000,   "spawn_rate": 10_000}, # 세션 종료, 급감
        {"duration": 720, "users": 0,        "spawn_rate": 10_000}, # 완전 종료
    ]

    def tick(self):
        run_time = self.get_run_time()
        for stage in self.stages:
            if run_time < stage["duration"]:
                return stage["users"], stage["spawn_rate"]
        return None
