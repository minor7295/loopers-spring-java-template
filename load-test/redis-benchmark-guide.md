# Redis Benchmark 테스트 가이드

Redis 서버의 성능을 측정하기 위한 `redis-benchmark` 사용 가이드입니다.

## 빠른 시작

### 1. Redis 서버 실행

```bash
docker-compose -f docker/infra-compose.yml up -d redis-master
```

### 2. Redis 연결 확인

```bash
redis-cli -h localhost -p 6379 PING
# 응답: PONG
```

### 3. 벤치마크 실행

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

## 테스트 시나리오 상세

### 시나리오 1: 기본 벤치마크

**목적**: GET/SET 명령어의 기본 성능 측정

**실행 명령어**:
```bash
redis-benchmark -h localhost -p 6379 -t get,set -n 100000 -c 50 -d 100 --csv
```

**주요 옵션**:
- `-t get,set`: 테스트할 명령어
- `-n 100000`: 총 요청 수
- `-c 50`: 동시 연결 수
- `-d 100`: 데이터 크기 (bytes)
- `--csv`: CSV 형식으로 출력

**예상 결과**:
```
"GET","100000","50","100","50000.00","0.020"
"SET","100000","50","100","45000.00","0.022"
```

### 시나리오 2: 다양한 명령어 테스트

**목적**: 다양한 Redis 명령어의 성능 비교

**실행 명령어**:
```bash
redis-benchmark -h localhost -p 6379 \
    -t get,set,incr,lpush,rpush,lpop,rpop,sadd,hset,spop,lrange \
    -n 10000 -c 10 -d 100 --csv
```

**분석 포인트**:
- 어떤 명령어가 가장 빠른가?
- 어떤 명령어가 가장 느린가?
- 명령어별 처리량 차이

### 시나리오 3: 동시 연결 수별 성능

**목적**: 동시 연결 수 증가에 따른 성능 변화 확인

**테스트 순서**:
1. 1개 동시 연결
2. 10개 동시 연결
3. 50개 동시 연결
4. 100개 동시 연결

**분석 포인트**:
- 동시 연결 수 증가 시 처리량 변화
- 최적 동시 연결 수 찾기
- 서버 리소스 사용량 변화

**예상 결과**:
- 동시 연결 수가 적을 때: 낮은 처리량, 낮은 지연
- 동시 연결 수가 많을 때: 높은 처리량, 높은 지연
- 최적 지점: 처리량과 지연의 균형점

### 시나리오 4: 데이터 크기별 성능

**목적**: 데이터 크기에 따른 성능 변화 확인

**테스트 데이터 크기**:
- 10 bytes (작은 데이터)
- 100 bytes (중간 데이터)
- 1,000 bytes (큰 데이터)
- 10,000 bytes (매우 큰 데이터)

**분석 포인트**:
- 데이터 크기 증가에 따른 처리량 감소
- 네트워크 대역폭 영향
- 메모리 사용량 변화

**예상 결과**:
- 작은 데이터: 높은 처리량 (50,000+ req/s)
- 큰 데이터: 낮은 처리량 (10,000-20,000 req/s)

### 시나리오 5: 파이프라인 테스트

**목적**: 파이프라인 사용 시 성능 향상 확인

**실행 명령어**:
```bash
redis-benchmark -h localhost -p 6379 \
    -t get,set -n 100000 -c 50 -d 100 -P 16 --csv
```

**파이프라인 크기 옵션**:
- `-P 1`: 파이프라인 미사용
- `-P 16`: 파이프라인 크기 16
- `-P 32`: 파이프라인 크기 32
- `-P 64`: 파이프라인 크기 64

**분석 포인트**:
- 파이프라인 사용 시 처리량 증가
- 최적 파이프라인 크기 찾기
- 네트워크 왕복 횟수 감소 효과

**예상 결과**:
- 파이프라인 미사용: 50,000 req/s
- 파이프라인 사용 (P=16): 200,000+ req/s

## 커스텀 테스트

### 특정 명령어만 테스트

```bash
# GET만
redis-benchmark -h localhost -p 6379 -t get -n 100000 -c 50

# SET만
redis-benchmark -h localhost -p 6379 -t set -n 100000 -c 50

# HSET (Hash)만
redis-benchmark -h localhost -p 6379 -t hset -n 100000 -c 50
```

### 특정 데이터 크기로 테스트

```bash
# 1KB 데이터
redis-benchmark -h localhost -p 6379 -t get,set -n 100000 -c 50 -d 1024

# 10KB 데이터
redis-benchmark -h localhost -p 6379 -t get,set -n 100000 -c 50 -d 10240
```

### 긴 테스트 실행

```bash
# 1시간 동안 테스트
redis-benchmark -h localhost -p 6379 -t get,set -n 1000000 -c 100 -d 100 --csv > long_test.csv
```

## 결과 분석

### CSV 출력 형식

```
"command","requests","clients","bytes","requests_per_sec","latency_ms"
"GET","100000","50","100","50000.00","0.020"
"SET","100000","50","100","45000.00","0.022"
```

### 주요 지표 설명

1. **requests_per_sec**: 초당 처리 가능한 요청 수
   - 높을수록 좋음
   - 일반적으로 50,000+ req/s 이상이면 우수

2. **latency_ms**: 요청당 평균 지연 시간 (밀리초)
   - 낮을수록 좋음
   - 일반적으로 1ms 이하가 우수

3. **throughput**: 전체 처리량
   - requests_per_sec × 데이터 크기

### 성능 비교 예시

#### 기본 GET/SET (100 bytes)
```
GET:  50,000 req/s, 0.020ms latency
SET:  45,000 req/s, 0.022ms latency
```

#### 파이프라인 사용 (P=16)
```
GET:  200,000 req/s, 0.080ms latency
SET:  180,000 req/s, 0.090ms latency
```

#### 대용량 데이터 (10KB)
```
GET:  15,000 req/s, 0.067ms latency
SET:  12,000 req/s, 0.083ms latency
```

## Eviction 정책 테스트

메모리 제한이 설정된 경우 eviction 정책의 효과를 테스트할 수 있습니다.

### 1. 메모리 제한 설정 확인

```bash
redis-cli CONFIG GET maxmemory
redis-cli CONFIG GET maxmemory-policy
```

### 2. 메모리 사용량 모니터링

```bash
# 실시간 모니터링
redis-cli --stat

# 메모리 정보
redis-cli INFO memory
```

### 3. Eviction 발생 확인

```bash
# Eviction 통계
redis-cli INFO stats | grep evicted_keys

# 실시간 eviction 모니터링
watch -n 1 'redis-cli INFO stats | grep evicted_keys'
```

### 4. Eviction 정책별 테스트

```bash
# volatile-lru 정책
docker-compose -f docker/infra-compose.yml restart redis-master
./redis-benchmark.sh > results_volatile_lru.txt

# allkeys-lru 정책
# docker/infra-compose.yml에서 maxmemory-policy 변경 후
docker-compose -f docker/infra-compose.yml restart redis-master
./redis-benchmark.sh > results_allkeys_lru.txt
```

## 성능 최적화 팁

### 1. 파이프라인 사용

애플리케이션에서 여러 명령어를 한 번에 전송:

```java
// Spring Data Redis 예시
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (int i = 0; i < 100; i++) {
        connection.set(("key" + i).getBytes(), ("value" + i).getBytes());
    }
    return null;
});
```

### 2. 연결 풀링

애플리케이션에서 연결을 재사용:

```yaml
# application.yml
spring:
  redis:
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
```

### 3. 데이터 구조 최적화

- **작은 데이터**: String 사용
- **필드가 많은 객체**: Hash 사용
- **리스트**: List 또는 Set 사용

### 4. 네트워크 최적화

- 같은 네트워크 내에서 실행
- Unix 소켓 사용 (로컬 서버인 경우)

## 문제 해결

### Redis 연결 실패

```bash
# Redis 서버 상태 확인
docker ps | grep redis

# Redis 로그 확인
docker logs redis-master

# 포트 확인
netstat -an | grep 6379
```

### 낮은 성능

1. **시스템 리소스 확인**
   ```bash
   # CPU 사용량
   top
   
   # 메모리 사용량
   free -h
   
   # 디스크 I/O
   iostat
   ```

2. **Redis 설정 확인**
   ```bash
   redis-cli CONFIG GET "*"
   ```

3. **네트워크 지연 확인**
   ```bash
   ping localhost
   redis-cli --latency
   ```

## 참고 자료

- [Redis Benchmark 공식 문서](https://redis.io/docs/management/optimization/benchmarks/)
- [Redis Performance Tuning](https://redis.io/docs/management/optimization/)
- [Redis Commands](https://redis.io/commands/)

