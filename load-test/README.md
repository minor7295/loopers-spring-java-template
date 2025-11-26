# 상품 조회 부하 테스트 가이드

Commerce API의 상품 조회 성능을 테스트하기 위한 Locust 부하 테스트 스크립트입니다.
**인덱스와 캐시의 성능 차이**를 확인하는 데 특화되어 있습니다.

## 설치

```bash
# Python 가상환경 생성 (선택사항)
python3 -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# Locust 설치
pip install -r requirements.txt
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

#### 인덱스 성능 테스트만 실행
```bash
# 인덱스 사용 여부에 따른 성능 차이 확인
locust -f locustfile.py --host=http://localhost:8080 --tags index --headless -u 50 -r 5 -t 3m
```

#### 캐시 성능 테스트만 실행
```bash
# 캐시 히트/미스에 따른 성능 차이 확인
locust -f locustfile.py --host=http://localhost:8080 --tags cache --headless -u 50 -r 5 -t 3m
```

#### 캐시 히트 시나리오만 실행
```bash
# 캐시된 데이터 조회 (첫 페이지, 상세 정보)
locust -f locustfile.py --host=http://localhost:8080 --tags cache_hit --headless -u 50 -r 5 -t 3m
```

#### 캐시 미스 시나리오만 실행
```bash
# 캐시되지 않은 데이터 조회 (다른 페이지, 다양한 상품)
locust -f locustfile.py --host=http://localhost:8080 --tags cache_miss --headless -u 50 -r 5 -t 3m
```

### 4. 결과 저장

```bash
# CSV로 결과 저장
locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m --csv=results

# HTML 리포트 저장
locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m --html=report.html
```

## 테스트 시나리오

### 1. 인덱스 성능 테스트 (`index` 태그)

다양한 정렬 옵션과 브랜드 필터 조합을 테스트하여 인덱스 사용 여부에 따른 성능 차이를 확인합니다.

#### 테스트 케이스:
- **전체 조회 + 최신순**: `idx_product_created` 또는 인덱스 없음
- **전체 조회 + 좋아요순**: `idx_product_likes` 인덱스 사용
- **전체 조회 + 가격순**: `idx_product_price` 인덱스 사용
- **브랜드 필터 + 최신순**: `idx_product_brand_created` 인덱스 사용
- **브랜드 필터 + 좋아요순**: `idx_product_brand_likes` 인덱스 사용
- **브랜드 필터 + 가격순**: `idx_product_brand_price` 인덱스 사용

#### 예상 결과:
- 인덱스가 있는 쿼리: **10~50ms**
- 인덱스가 없는 쿼리: **500ms 이상**

### 2. 캐시 성능 테스트 (`cache` 태그)

캐시 히트/미스에 따른 성능 차이를 확인합니다.

#### 캐시 히트 시나리오 (`cache_hit`):
- **첫 페이지 조회** (page=0): Redis 캐시에서 조회
- **상품 상세 조회**: Redis 캐시에서 조회
- **브랜드별 첫 페이지**: Redis 캐시에서 조회

#### 캐시 미스 시나리오 (`cache_miss`):
- **다른 페이지 조회** (page > 0): DB에서 직접 조회
- **다양한 상품 상세**: 캐시되지 않은 상품 조회

#### 예상 결과:
- 캐시 히트: **1~5ms**
- 캐시 미스: **10~50ms** (인덱스 사용 시)

### 3. 페이지네이션 테스트 (`pagination` 태그)

페이지 깊이에 따른 성능 차이를 확인합니다.

- **얕은 페이지** (page 0~10): 빠른 응답
- **깊은 페이지** (page 20~50): OFFSET이 커서 성능 저하 가능

## 사용자 클래스

### ProductQueryUser (기본)
모든 테스트 시나리오를 포함한 기본 사용자 클래스입니다.

### IndexPerformanceUser
인덱스 성능 테스트에 특화된 사용자 클래스입니다.
```bash
locust -f locustfile.py --host=http://localhost:8080 --class IndexPerformanceUser
```

### CachePerformanceUser
캐시 성능 테스트에 특화된 사용자 클래스입니다.
```bash
locust -f locustfile.py --host=http://localhost:8080 --class CachePerformanceUser
```

### MixedLoadUser
실제 사용자 패턴을 시뮬레이션하는 혼합 부하 테스트입니다.
```bash
locust -f locustfile.py --host=http://localhost:8080 --class MixedLoadUser
```

## 태그 목록

- `index`: 인덱스 성능 테스트
- `cache`: 캐시 성능 테스트
- `cache_hit`: 캐시 히트 시나리오
- `cache_miss`: 캐시 미스 시나리오
- `list`: 상품 목록 조회
- `detail`: 상품 상세 조회
- `pagination`: 페이지네이션 테스트

## 성능 비교 테스트 가이드

### 1. 인덱스 유무에 따른 성능 비교

#### 인덱스 있는 경우
```bash
# 인덱스가 적용된 상태에서 테스트
locust -f locustfile.py --host=http://localhost:8080 --tags index --headless -u 100 -r 10 -t 5m --csv=results_with_index
```

#### 인덱스 없는 경우 (인덱스 제거 후)
```bash
# 인덱스를 제거한 상태에서 테스트
# 1. DB에서 인덱스 제거
# 2. 테스트 실행
locust -f locustfile.py --host=http://localhost:8080 --tags index --headless -u 100 -r 10 -t 5m --csv=results_without_index
```

#### 결과 비교
- `results_with_index_stats.csv`와 `results_without_index_stats.csv` 비교
- 평균 응답 시간, 95th percentile 등 비교

### 2. 캐시 유무에 따른 성능 비교

#### 캐시 활성화 상태
```bash
# Redis 캐시가 활성화된 상태에서 테스트
locust -f locustfile.py --host=http://localhost:8080 --tags cache_hit --headless -u 100 -r 10 -t 5m --csv=results_with_cache
```

#### 캐시 비활성화 상태 (Redis 중지 또는 캐시 로직 주석 처리)
```bash
# Redis를 중지하거나 캐시 로직을 비활성화한 상태에서 테스트
locust -f locustfile.py --host=http://localhost:8080 --tags cache_hit --headless -u 100 -r 10 -t 5m --csv=results_without_cache
```

## 주의사항

1. **데이터 준비**: 부하 테스트 전에 충분한 테스트 데이터를 생성해야 합니다.
   - 상품: 최소 10,000개 이상 권장
   - 브랜드: 100개 이상 권장
   - 데이터 시딩: `apps/commerce-api/scripts/run-seeding.sh` 실행

2. **인덱스 상태 확인**: 테스트 전에 인덱스가 제대로 생성되었는지 확인하세요.
   ```sql
   SHOW INDEX FROM product;
   ```

3. **캐시 상태 확인**: Redis가 실행 중인지 확인하세요.
   ```bash
   redis-cli ping
   ```

4. **서버 모니터링**: 테스트 중 서버의 CPU, 메모리, DB 연결 풀 상태를 모니터링하세요.

## 예시 실행 명령어

```bash
# 경량 테스트 (10명, 1분)
cd load_test
locust -f locustfile.py --host=http://localhost:8080 --headless -u 10 -r 2 -t 1m

# 인덱스 성능 테스트 (50명, 3분)
locust -f locustfile.py --host=http://localhost:8080 --tags index --headless -u 50 -r 5 -t 3m --csv=index_test

# 캐시 성능 테스트 (50명, 3분)
locust -f locustfile.py --host=http://localhost:8080 --tags cache --headless -u 50 -r 5 -t 3m --csv=cache_test

# 혼합 부하 테스트 (100명, 5분)
locust -f locustfile.py --host=http://localhost:8080 --class MixedLoadUser --headless -u 100 -r 10 -t 5m --csv=mixed_test

# 고부하 테스트 (500명, 10분)
locust -f locustfile.py --host=http://localhost:8080 --headless -u 500 -r 50 -t 10m --csv=high_load_test
```

## 결과 분석 팁

1. **평균 응답 시간**: 각 엔드포인트별 평균 응답 시간 비교
2. **95th percentile**: 95%의 요청이 이 시간 이내에 완료
3. **RPS (Requests Per Second)**: 초당 처리 가능한 요청 수
4. **실패율**: 에러 발생 비율 확인

인덱스와 캐시의 효과를 정량적으로 확인할 수 있습니다!

---

## Redis Benchmark 테스트

Redis 서버의 성능을 측정하기 위한 `redis-benchmark` 테스트 스크립트입니다.

### 사전 요구사항

1. **Redis 서버 실행**
   ```bash
   docker-compose -f docker/infra-compose.yml up -d redis-master
   ```

2. **redis-cli 및 redis-benchmark 설치**
   - Linux/Mac: `sudo apt-get install redis-tools` 또는 `brew install redis`
   - Windows: Redis for Windows 설치 또는 WSL 사용

### 사용 방법

#### Linux/Mac

```bash
cd load-test
chmod +x redis-benchmark.sh
./redis-benchmark.sh
```

#### Windows

```cmd
cd load-test
redis-benchmark.bat
```

#### 환경변수 설정 (선택사항)

```bash
# Redis 호스트 및 포트 설정
export REDIS_HOST=localhost
export REDIS_PORT=6379
./redis-benchmark.sh
```

### 테스트 시나리오

스크립트는 다음 5가지 테스트를 자동으로 실행합니다:

#### 1. 기본 벤치마크 (GET/SET)
- **목적**: 기본적인 GET/SET 명령어 성능 측정
- **설정**: 100,000개 요청, 50개 동시 연결, 100 bytes 데이터

#### 2. 다양한 명령어 벤치마크
- **목적**: 다양한 Redis 명령어의 성능 측정
- **명령어**: GET, SET, INCR, LPUSH, RPUSH, LPOP, RPOP, SADD, HSET, SPOP, LRANGE
- **설정**: 10,000개 요청, 10개 동시 연결

#### 3. 동시 연결 수별 성능 테스트
- **목적**: 동시 연결 수에 따른 성능 변화 확인
- **테스트**: 1, 10, 50, 100개 동시 연결
- **분석**: 동시 연결 수 증가에 따른 처리량 변화 확인

#### 4. 데이터 크기별 성능 테스트
- **목적**: 데이터 크기에 따른 성능 변화 확인
- **테스트**: 10, 100, 1,000, 10,000 bytes
- **분석**: 데이터 크기 증가에 따른 처리량 감소 확인

#### 5. 파이프라인 테스트
- **목적**: 파이프라인 사용 시 성능 향상 확인
- **설정**: 파이프라인 크기 16, 100,000개 요청
- **분석**: 파이프라인 사용 시 처리량 증가 확인

### 결과 해석

#### 주요 지표

- **requests per second**: 초당 처리 가능한 요청 수 (높을수록 좋음)
- **latency**: 요청당 평균 지연 시간 (낮을수록 좋음)
- **throughput**: 전체 처리량

#### 예상 성능 (일반적인 Redis 서버)

- **GET/SET (100 bytes)**: 50,000~100,000 requests/sec
- **파이프라인 사용 시**: 200,000~500,000 requests/sec
- **대용량 데이터 (10KB)**: 10,000~20,000 requests/sec

### 커스텀 벤치마크 실행

#### 특정 명령어만 테스트

```bash
# GET 명령어만 테스트
redis-benchmark -h localhost -p 6379 -t get -n 100000 -c 50

# SET 명령어만 테스트
redis-benchmark -h localhost -p 6379 -t set -n 100000 -c 50
```

#### 특정 데이터 크기로 테스트

```bash
# 1KB 데이터로 테스트
redis-benchmark -h localhost -p 6379 -t get,set -n 100000 -c 50 -d 1024

# 10KB 데이터로 테스트
redis-benchmark -h localhost -p 6379 -t get,set -n 100000 -c 50 -d 10240
```

#### 파이프라인 크기 조정

```bash
# 파이프라인 크기 32로 테스트
redis-benchmark -h localhost -p 6379 -t get,set -n 100000 -c 50 -P 32
```

### Eviction 정책 테스트

메모리 제한이 설정된 경우 eviction 정책의 효과를 테스트할 수 있습니다:

```bash
# 메모리 제한 확인
redis-cli CONFIG GET maxmemory
redis-cli CONFIG GET maxmemory-policy

# 메모리 사용량 확인
redis-cli INFO memory

# Eviction 발생 확인
redis-cli INFO stats | grep evicted_keys
```

### 성능 최적화 팁

1. **파이프라인 사용**: 여러 명령어를 한 번에 전송하여 네트워크 오버헤드 감소
2. **연결 풀링**: 애플리케이션에서 연결을 재사용
3. **메모리 최적화**: 적절한 데이터 구조 선택 (String vs Hash vs List)
4. **네트워크 최적화**: 같은 네트워크 내에서 실행하거나 Unix 소켓 사용

### 주의사항

1. **프로덕션 환경 주의**: redis-benchmark는 높은 부하를 생성하므로 프로덕션 환경에서는 사용하지 마세요
2. **네트워크 지연**: 원격 Redis 서버의 경우 네트워크 지연이 결과에 영향을 줄 수 있습니다
3. **시스템 리소스**: 테스트 중 시스템의 CPU, 메모리 사용량을 모니터링하세요

### 결과 비교

다양한 Redis 설정에 따른 성능 비교:

```bash
# 기본 설정
./redis-benchmark.sh > results_default.txt

# 메모리 제한 설정 후
docker-compose -f docker/infra-compose.yml restart redis-master
./redis-benchmark.sh > results_limited_memory.txt

# Eviction 정책 변경 후
# docker/infra-compose.yml에서 maxmemory-policy 변경
docker-compose -f docker/infra-compose.yml restart redis-master
./redis-benchmark.sh > results_eviction.txt
```

### 참고 자료

- [Redis Benchmark 공식 문서](https://redis.io/docs/management/optimization/benchmarks/)
- [Redis Performance Tuning](https://redis.io/docs/management/optimization/)