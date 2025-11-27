# 캐시 모니터링 가이드

## 개요

부하 테스트 중 캐시 히트/미스 비율과 처리 경로를 확인할 수 있는 모니터링 기능이 추가되었습니다.

## 모니터링 방법

### 1. Prometheus 메트릭 확인

애플리케이션이 실행 중일 때 다음 엔드포인트에서 메트릭을 확인할 수 있습니다:

```
http://localhost:8081/actuator/prometheus
```

#### 주요 메트릭

##### 캐시 히트/미스
- `product_cache_hits_total`: 캐시 히트 수
  - 태그: `cache_type=product`, `operation=list|detail`
- `product_cache_misses_total`: 캐시 미스 수
  - 태그: `cache_type=product`, `operation=list|detail`, `reason=page_not_zero|not_found`
- `product_cache_saves_total`: 캐시 저장 성공 수
  - 태그: `cache_type=product`, `operation=list|detail`
- `product_cache_save_failures_total`: 캐시 저장 실패 수
  - 태그: `cache_type=product`, `operation=list|detail`
- `product_cache_query_failures_total`: 캐시 조회 실패 수
  - 태그: `cache_type=product`, `operation=list|detail`

#### 메트릭 쿼리 예시

**캐시 히트율 계산:**
```promql
# 전체 캐시 히트율
sum(rate(product_cache_hits_total[5m])) / 
(sum(rate(product_cache_hits_total[5m])) + sum(rate(product_cache_misses_total[5m]))) * 100

# 상품 목록 캐시 히트율
sum(rate(product_cache_hits_total{operation="list"}[5m])) / 
(sum(rate(product_cache_hits_total{operation="list"}[5m])) + 
 sum(rate(product_cache_misses_total{operation="list"}[5m]))) * 100

# 상품 상세 캐시 히트율
sum(rate(product_cache_hits_total{operation="detail"}[5m])) / 
(sum(rate(product_cache_hits_total{operation="detail"}[5m])) + 
 sum(rate(product_cache_misses_total{operation="detail"}[5m]))) * 100
```

**캐시 미스 원인 분석:**
```promql
# 첫 페이지가 아닌 경우 (정상)
sum(rate(product_cache_misses_total{reason="page_not_zero"}[5m]))

# 캐시에 없는 경우
sum(rate(product_cache_misses_total{reason="not_found"}[5m]))
```

**캐시 저장/조회 실패율:**
```promql
# 캐시 저장 실패율
sum(rate(product_cache_save_failures_total[5m])) / 
sum(rate(product_cache_saves_total[5m])) * 100

# 캐시 조회 실패율
sum(rate(product_cache_query_failures_total[5m])) / 
(sum(rate(product_cache_hits_total[5m])) + sum(rate(product_cache_misses_total[5m]))) * 100
```

### 2. 애플리케이션 로그 확인

#### 로그 레벨 설정

`application.yml` 또는 환경 변수로 로그 레벨을 조정:

```yaml
logging:
  level:
    com.loopers.application.catalog: DEBUG  # 상세 로그
    # 또는
    com.loopers.application.catalog: INFO   # 요약 로그
```

#### 로그 메시지 예시

**캐시 히트:**
```
DEBUG - 상품 목록 캐시 히트: key=product:list:brand:all:sort:latest:page:0:size:20
DEBUG - 상품 목록 조회: 캐시 히트 - brandId=null, sort=latest, page=0, size=20
```

**캐시 미스 (DB 조회):**
```
DEBUG - 상품 목록 캐시 미스: key=product:list:brand:all:sort:latest:page:0:size:20
DEBUG - 상품 목록 조회: DB 조회 시작 - brandId=null, sort=latest, page=0, size=20
DEBUG - 상품 목록 조회: DB 조회 완료 - brandId=null, sort=latest, page=0, size=20, count=20
```

**첫 페이지가 아닌 경우:**
```
DEBUG - 상품 목록 캐시 조회 스킵 (첫 페이지 아님): page=1
DEBUG - 상품 목록 조회: DB 조회 시작 - brandId=null, sort=latest, page=1, size=20
```

**캐시 저장:**
```
DEBUG - 상품 목록 캐시 저장 성공: key=product:list:brand:all:sort:latest:page:0:size:20, size=1234 bytes
```

### 3. Grafana 대시보드 (선택사항)

Prometheus와 Grafana가 설정되어 있다면 대시보드를 만들어 시각화할 수 있습니다.

#### 대시보드 패널 예시

1. **캐시 히트율 (Gauge)**
   - 쿼리: 캐시 히트율 계산 공식
   - 범위: 0-100%

2. **캐시 히트/미스 추이 (Graph)**
   - 쿼리: `rate(product_cache_hits_total[5m])`, `rate(product_cache_misses_total[5m])`
   - 범례: 히트, 미스

3. **캐시 저장/조회 실패율 (Graph)**
   - 쿼리: 실패율 계산 공식

## 부하 테스트 중 확인 방법

### 1. 실시간 메트릭 확인

```bash
# Prometheus 메트릭 확인
curl http://localhost:8081/actuator/prometheus | grep product_cache

# 특정 메트릭만 확인
curl http://localhost:8081/actuator/prometheus | grep "product_cache_hits_total"
curl http://localhost:8081/actuator/prometheus | grep "product_cache_misses_total"
```

### 2. 로그 파일 분석

```bash
# 캐시 히트만 확인
grep "캐시 히트" application.log

# 캐시 미스만 확인
grep "캐시 미스" application.log

# DB 조회만 확인
grep "DB 조회" application.log

# 통계 계산
echo "캐시 히트: $(grep -c '캐시 히트' application.log)"
echo "캐시 미스: $(grep -c '캐시 미스' application.log)"
echo "DB 조회: $(grep -c 'DB 조회 시작' application.log)"
```

### 3. 부하 테스트 후 분석

부하 테스트가 끝난 후 Prometheus에서 다음을 확인:

1. **전체 캐시 히트율**
   ```promql
   sum(product_cache_hits_total) / 
   (sum(product_cache_hits_total) + sum(product_cache_misses_total)) * 100
   ```

2. **상품 목록 vs 상세 캐시 히트율 비교**
   ```promql
   # 목록
   sum(product_cache_hits_total{operation="list"}) / 
   (sum(product_cache_hits_total{operation="list"}) + 
    sum(product_cache_misses_total{operation="list"})) * 100
   
   # 상세
   sum(product_cache_hits_total{operation="detail"}) / 
   (sum(product_cache_hits_total{operation="detail"}) + 
    sum(product_cache_misses_total{operation="detail"})) * 100
   ```

3. **캐시 미스 원인 분석**
   ```promql
   # 첫 페이지가 아닌 경우 (정상)
   sum(product_cache_misses_total{reason="page_not_zero"})
   
   # 캐시에 없는 경우 (캐시 워밍업 필요)
   sum(product_cache_misses_total{reason="not_found"})
   ```

## 예상 결과 해석

### 좋은 캐시 히트율
- **상품 목록**: 70-90% (첫 페이지만 캐시하므로)
- **상품 상세**: 50-80% (인기 상품은 높고, 비인기 상품은 낮음)

### 개선이 필요한 경우
- **캐시 히트율 < 30%**: 캐시 전략 재검토 필요
- **캐시 저장 실패율 > 5%**: Redis 리소스 부족 가능성
- **캐시 조회 실패율 > 1%**: Redis 연결 문제 가능성

## 문제 해결

### 캐시 히트율이 낮은 경우
1. 캐시 TTL 확인 (너무 짧으면 증가)
2. 캐시 범위 확인 (더 많은 페이지 캐시)
3. 캐시 워밍업 전략 검토

### 캐시 저장 실패가 많은 경우
1. Redis 리소스 확인 (메모리, CPU)
2. Redis 연결 상태 확인
3. 네트워크 지연 확인

### 캐시 조회 실패가 많은 경우
1. Redis 서버 상태 확인
2. Redis 연결 풀 설정 확인
3. 타임아웃 설정 확인

## 참고

- Prometheus 엔드포인트: `http://localhost:8081/actuator/prometheus`
- 로그 레벨: `DEBUG`로 설정하면 상세 로그 확인 가능
- 메트릭은 애플리케이션 재시작 시 초기화됨 (누적 값이 아님)

