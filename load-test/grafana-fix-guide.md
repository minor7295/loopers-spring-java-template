# Grafana 데이터 표시 문제 해결 가이드

Prometheus에서는 데이터가 확인되지만 Grafana에서 보이지 않는 경우의 해결 방법입니다.

## 1. Grafana 데이터소스 연결 확인

### Grafana UI에서 확인

1. **http://localhost:3000** 접속 (admin/admin)
2. 좌측 메뉴: **Configuration** → **Data Sources** 클릭
3. **Prometheus** 데이터소스 클릭
4. 하단의 **Save & Test** 버튼 클릭
   - ✅ **Data source is working**: 정상
   - ❌ **Error**: 연결 실패

### 연결 실패 시 확인사항

- **URL 확인**: `http://prometheus:9090` (Docker 네트워크 내부 주소)
- **Access 모드**: `Server (default)` 또는 `Proxy` 권장
- **Prometheus 컨테이너 이름**: `prometheus`와 일치하는지 확인

## 2. Explore에서 직접 쿼리 테스트

### Grafana Explore 사용

1. 좌측 메뉴: **Explore** 클릭
2. 상단에서 **Prometheus** 데이터소스 선택
3. 쿼리 입력창에 다음 쿼리 입력:

```promql
# HTTP 요청 수 확인
http_server_requests_seconds_count

# 특정 엔드포인트
http_server_requests_seconds_count{uri="/api/v1/products"}

# 요청률 (RPS)
rate(http_server_requests_seconds_count[1m])
```

4. **Run query** 클릭하여 데이터 확인

**데이터가 보이면**: 데이터소스는 정상, 대시보드 문제
**데이터가 안 보이면**: 데이터소스 연결 문제 또는 시간 범위 문제

## 3. 시간 범위 확인

### 시간 범위 설정

1. Grafana 우측 상단의 **시간 선택기** 클릭
2. **Last 5 minutes** 또는 **Last 15 minutes** 선택
3. 또는 **Custom range**에서 최근 시간 범위 지정

**중요**: Prometheus에 데이터가 있어도 시간 범위가 맞지 않으면 표시되지 않습니다.

## 4. Spring Boot 메트릭 대시보드 생성

### 수동으로 패널 추가

1. **Dashboards** → **New Dashboard** 클릭
2. **Add visualization** 클릭
3. **Prometheus** 데이터소스 선택
4. 쿼리 입력:

#### 패널 1: HTTP 요청 수 (Total)

```promql
sum(rate(http_server_requests_seconds_count[1m]))
```

- **Panel title**: Total Requests per Second
- **Legend**: Total RPS

#### 패널 2: 엔드포인트별 요청 수

```promql
sum(rate(http_server_requests_seconds_count[1m])) by (uri)
```

- **Panel title**: Requests by Endpoint
- **Legend**: {{uri}}

#### 패널 3: HTTP 상태 코드별 요청 수

```promql
sum(rate(http_server_requests_seconds_count[1m])) by (status)
```

- **Panel title**: Requests by Status Code
- **Legend**: Status {{status}}

#### 패널 4: 응답 시간 (평균)

```promql
avg(http_server_requests_seconds_sum / http_server_requests_seconds_count) by (uri)
```

- **Panel title**: Average Response Time
- **Unit**: seconds

#### 패널 5: 응답 시간 (95th percentile)

```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[1m])) by (le, uri))
```

- **Panel title**: 95th Percentile Response Time
- **Unit**: seconds

## 5. 자동 프로비저닝 대시보드 생성

프로젝트에 대시보드를 자동으로 로드하도록 설정할 수 있습니다.

### 대시보드 디렉토리 생성

```bash
mkdir -p docker/grafana/provisioning/dashboards
```

### 대시보드 프로비저닝 설정

`docker/grafana/provisioning/dashboards/dashboards.yml` 파일 생성:

```yaml
apiVersion: 1

providers:
  - name: 'Spring Boot Metrics'
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    allowUiUpdates: true
    options:
      path: /etc/grafana/provisioning/dashboards
      foldersFromFilesStructure: true
```

### docker-compose.yml 수정

`docker/monitoring-compose.yml`에 볼륨 추가:

```yaml
grafana:
  volumes:
    - ./grafana/provisioning:/etc/grafana/provisioning
    - ./grafana/dashboards:/etc/grafana/provisioning/dashboards  # 추가
```

## 6. 빠른 테스트 쿼리

Grafana Explore에서 다음 쿼리들을 순서대로 테스트:

### 1단계: 기본 메트릭 확인

```promql
up
```
→ Prometheus 자체 상태 확인 (항상 1이어야 함)

### 2단계: 애플리케이션 메트릭 확인

```promql
http_server_requests_seconds_count
```
→ HTTP 요청 메트릭이 있는지 확인

### 3단계: 메트릭 레이블 확인

```promql
{__name__=~"http_server_requests.*"}
```
→ 모든 HTTP 관련 메트릭 확인

### 4단계: 특정 메트릭 확인

```promql
http_server_requests_seconds_count{application="commerce-api"}
```
→ 애플리케이션 이름으로 필터링

## 7. 일반적인 문제 해결

### 문제 1: "No data" 표시

**원인:**
- 시간 범위가 데이터가 없는 기간
- 쿼리가 잘못됨
- 메트릭 이름이 다름

**해결:**
1. 시간 범위를 **Last 15 minutes**로 변경
2. Explore에서 간단한 쿼리로 테스트: `up`
3. Prometheus UI에서 실제 메트릭 이름 확인

### 문제 2: "Query failed" 에러

**원인:**
- 데이터소스 연결 실패
- PromQL 문법 오류

**해결:**
1. 데이터소스 설정 확인 (Configuration → Data Sources)
2. Prometheus UI에서 동일 쿼리 테스트
3. 쿼리 문법 확인

### 문제 3: 데이터는 있지만 그래프가 안 그려짐

**원인:**
- 시계열 데이터 형식 문제
- 시간 범위 문제

**해결:**
1. **Format as**: Time series 확인
2. 시간 범위 확대
3. **Legend** 설정 확인

## 8. 메트릭 이름 확인

Spring Boot Actuator가 생성하는 실제 메트릭 이름을 확인:

```bash
# 애플리케이션에서 직접 확인
curl http://localhost:8081/actuator/prometheus | grep http_server
```

일반적인 메트릭 이름:
- `http_server_requests_seconds_count` - 요청 수
- `http_server_requests_seconds_sum` - 총 응답 시간
- `http_server_requests_seconds_max` - 최대 응답 시간
- `http_server_requests_seconds_bucket` - 히스토그램 (percentile 계산용)

## 9. 디버깅 체크리스트

- [ ] Grafana 데이터소스가 "Data source is working" 상태인가?
- [ ] Explore에서 `up` 쿼리가 1을 반환하는가?
- [ ] Explore에서 `http_server_requests_seconds_count` 쿼리에 데이터가 있는가?
- [ ] 시간 범위가 올바른가? (Last 15 minutes)
- [ ] Prometheus UI에서 동일한 쿼리가 작동하는가?
- [ ] 애플리케이션에 실제 요청이 발생했는가?

## 10. 빠른 해결 방법

### 방법 1: Explore에서 직접 확인

1. Grafana → Explore
2. Prometheus 선택
3. 쿼리: `http_server_requests_seconds_count`
4. 시간 범위: Last 15 minutes
5. Run query

### 방법 2: 간단한 대시보드 생성

1. Dashboards → New Dashboard
2. Add visualization
3. 쿼리: `sum(rate(http_server_requests_seconds_count[1m]))`
4. Save dashboard

### 방법 3: 기존 대시보드 임포트

Grafana 공식 대시보드 사용:
- Dashboard ID: **11378** (Spring Boot 2.1 Statistics)
- 또는 **4701** (JVM Micrometer)

**임포트 방법:**
1. Dashboards → Import
2. Dashboard ID 입력
3. Prometheus 데이터소스 선택
4. Import

