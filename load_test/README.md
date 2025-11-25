# 부하 테스트 (Load Test)

Commerce API의 성능을 테스트하기 위한 Locust 부하 테스트 스크립트입니다.

## 설치

```bash
# Python 가상환경 생성 (선택사항)
python3 -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# Locust 설치
pip install -r requirements-locust.txt
```

## 사용 방법

### 1. 웹 UI 모드 (대화형)

```bash
cd load_test
locust -f locustfile.py --host=http://localhost:8080
```

브라우저에서 `http://localhost:8089`로 접속하여 테스트를 시작할 수 있습니다.

### 2. 헤드리스 모드 (CLI)

```bash
# 기본 실행
cd load_test
locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m

# 옵션 설명:
# -u, --users: 동시 사용자 수
# -r, --spawn-rate: 초당 생성할 사용자 수
# -t, --run-time: 테스트 실행 시간 (예: 5m, 1h, 30s)
```

### 3. 태그별 실행

특정 API 그룹만 테스트할 수 있습니다:

```bash
# 카탈로그 API만 테스트
locust -f locustfile.py --host=http://localhost:8080 --tags catalog

# 읽기 전용 API만 테스트
locust -f locustfile.py --host=http://localhost:8080 --tags read

# 쓰기 API만 테스트
locust -f locustfile.py --host=http://localhost:8080 --tags write

# 여러 태그 조합
locust -f locustfile.py --host=http://localhost:8080 --tags catalog,read
```

### 4. 결과 저장

```bash
# CSV로 결과 저장
locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m --csv=results

# HTML 리포트 저장
locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m --html=report.html
```

## 테스트 시나리오

### 기본 시나리오 (CommerceApiUser)

모든 API를 포함한 종합 테스트:
- 상품 목록/상세 조회 (가장 빈번)
- 브랜드 조회
- 사용자 정보 조회
- 포인트 조회/충전
- 좋아요 추가/삭제/목록 조회
- 주문 생성/조회

### 웹사이트 사용자 시나리오 (WebsiteUser)

읽기 중심의 시나리오:
- 상품 둘러보기
- 브랜드 보기

### 모바일 앱 사용자 시나리오 (MobileAppUser)

좋아요와 주문을 포함한 시나리오:
- 상품 조회
- 좋아요 추가 (30% 확률)
- 주문 생성 (10% 확률)

## 태그 목록

- `catalog`: 카탈로그 관련 API (상품, 브랜드)
- `user`: 사용자 정보 API
- `point`: 포인트 관련 API
- `like`: 좋아요 관련 API
- `order`: 주문 관련 API
- `read`: 읽기 전용 API
- `write`: 쓰기 API

## 주의사항

1. **사용자 ID 범위**: `on_start()` 메서드에서 사용자 ID 범위를 실제 데이터에 맞게 조정하세요.
2. **상품 ID 범위**: 실제 데이터베이스의 상품 수에 맞게 조정하세요.
3. **서버 주소**: `--host` 옵션을 실제 테스트 대상 서버 주소로 변경하세요.
4. **데이터 시딩**: 부하 테스트 전에 충분한 테스트 데이터를 생성해야 합니다.

## 성능 모니터링 팁

1. **단계적 부하 증가**: 처음에는 낮은 사용자 수로 시작하여 점진적으로 증가시키세요.
2. **모니터링 도구**: 서버의 CPU, 메모리, 네트워크 사용량을 모니터링하세요.
3. **데이터베이스 모니터링**: 쿼리 성능과 연결 풀 상태를 확인하세요.
4. **캐시 효과**: Redis 캐시의 히트율을 확인하세요.

## 예시 실행 명령어

```bash
# 경량 테스트 (10명, 1분)
cd load_test
locust -f locustfile.py --host=http://localhost:8080 --headless -u 10 -r 2 -t 1m

# 중간 부하 테스트 (100명, 5분)
locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m

# 고부하 테스트 (500명, 10분)
locust -f locustfile.py --host=http://localhost:8080 --headless -u 500 -r 50 -t 10m

# 카탈로그 API만 집중 테스트 (200명, 3분)
locust -f locustfile.py --host=http://localhost:8080 --headless -u 200 -r 20 -t 3m --tags catalog
```
