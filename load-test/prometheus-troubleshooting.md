# Prometheus 데이터 수신 문제 해결 가이드

Grafana에서 요청 데이터가 보이지 않을 때 Prometheus가 데이터를 수신하는지 확인하는 방법입니다.

## 1. Prometheus 타겟 상태 확인

### Prometheus UI에서 확인

1. 브라우저에서 `http://localhost:9090` 접속
2. 상단 메뉴에서 **Status** → **Targets** 클릭
3. `spring-boot-app` 타겟의 상태 확인:
   - ✅ **UP** (녹색): 정상적으로 메트릭을 수집 중
   - ❌ **DOWN** (빨간색): 연결 실패 또는 타임아웃

### 타겟이 DOWN인 경우

**가능한 원인:**
- 애플리케이션이 실행되지 않음
- 포트 불일치 (Prometheus는 8081 포트를 기대)
- 네트워크 연결 문제 (`host.docker.internal` 접근 불가)

## 2. 애플리케이션 메트릭 엔드포인트 직접 확인

### 로컬에서 확인

```bash
# Actuator Prometheus 엔드포인트 확인
curl http://localhost:8081/actuator/prometheus

# 또는 브라우저에서 접속
# http://localhost:8081/actuator/prometheus
```

**예상 응답:**
```
# HELP http_server_requests_seconds...
# TYPE http_server_requests_seconds summary
http_server_requests_seconds_count{application="commerce-api",method="GET",uri="/api/v1/products",status="200"} 150.0
...
```

**응답이 없는 경우:**
- 애플리케이션이 실행 중인지 확인
- `management.server.port` 설정 확인 (기본값: 8081)
- `management.endpoints.web.exposure.include=prometheus` 설정 확인

## 3. Prometheus 설정 확인

### 현재 설정 확인

`docker/grafana/prometheus.yml` 파일 확인:

```yaml
scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8081']  # ← 이 주소가 올바른지 확인
```

### Docker 컨테이너에서 확인

```bash
# Prometheus 컨테이너 내부에서 타겟 접근 테스트
docker exec -it <prometheus-container-id> wget -O- http://host.docker.internal:8081/actuator/prometheus

# 또는 curl 사용
docker exec -it <prometheus-container-id> curl http://host.docker.internal:8081/actuator/prometheus
```

**Windows에서 `host.docker.internal` 문제:**
- Windows에서는 `host.docker.internal`이 자동으로 호스트 IP로 변환됨
- 만약 작동하지 않으면, 실제 호스트 IP로 변경 필요

## 4. Prometheus 쿼리로 데이터 확인

### Prometheus UI에서 직접 쿼리

1. `http://localhost:9090` 접속
2. 상단 쿼리 입력창에 다음 쿼리 입력:

```promql
# HTTP 요청 수 확인
http_server_requests_seconds_count

# 특정 엔드포인트의 요청 수
http_server_requests_seconds_count{uri="/api/v1/products"}

# 애플리케이션별 메트릭 확인
http_server_requests_seconds_count{application="commerce-api"}

# 최근 5분간의 데이터
http_server_requests_seconds_count[5m]
```

**데이터가 없는 경우:**
- 타겟이 DOWN 상태인지 확인
- 스크랩 간격이 너무 길지 않은지 확인 (현재: 5초)
- 애플리케이션에 실제 요청이 발생했는지 확인

## 5. 단계별 진단 체크리스트

### ✅ 체크 1: 애플리케이션 실행 확인

```bash
# 애플리케이션이 실행 중인지 확인
curl http://localhost:8080/api/v1/products

# Actuator 엔드포인트 확인
curl http://localhost:8081/actuator/health
curl http://localhost:8081/actuator/prometheus
```

### ✅ 체크 2: Prometheus 컨테이너 실행 확인

```bash
# Docker 컨테이너 상태 확인
docker ps | grep prometheus

# Prometheus 로그 확인
docker logs <prometheus-container-id>
```

### ✅ 체크 3: 네트워크 연결 확인

```bash
# Prometheus 컨테이너에서 호스트 접근 테스트
docker exec -it <prometheus-container-id> ping host.docker.internal

# 포트 접근 테스트
docker exec -it <prometheus-container-id> nc -zv host.docker.internal 8081
```

### ✅ 체크 4: 설정 파일 확인

```bash
# Prometheus 설정 파일 확인
cat docker/grafana/prometheus.yml

# Prometheus가 실제로 사용하는 설정 확인 (컨테이너 내부)
docker exec -it <prometheus-container-id> cat /etc/prometheus/prometheus.yml
```

## 6. 일반적인 문제 해결

### 문제 1: 타겟이 DOWN 상태

**해결 방법:**

1. **포트 확인**: 애플리케이션이 8081 포트에서 실행 중인지 확인
   ```yaml
   # supports/monitoring/src/main/resources/monitoring.yml
   management:
     server:
       port: 8081  # ← 이 포트가 열려있는지 확인
   ```

2. **네트워크 설정 변경**: `host.docker.internal` 대신 실제 IP 사용
   ```yaml
   # docker/grafana/prometheus.yml
   static_configs:
     - targets: ['172.17.0.1:8081']  # Docker 기본 게이트웨이
     # 또는
     - targets: ['<실제-호스트-IP>:8081']
   ```

3. **Docker 네트워크 모드 변경**: `network_mode: host` 사용 (Linux만 가능)

### 문제 2: 메트릭은 수집되지만 Grafana에 표시 안 됨

**해결 방법:**

1. **Grafana 데이터소스 확인**
   - Grafana UI: Configuration → Data Sources → Prometheus
   - URL이 `http://prometheus:9090`인지 확인

2. **대시보드 쿼리 확인**
   - 대시보드의 쿼리가 올바른 메트릭 이름을 사용하는지 확인
   - 예: `http_server_requests_seconds_count` (Spring Boot 2.x+)

3. **시간 범위 확인**
   - Grafana의 시간 범위가 데이터가 있는 기간과 일치하는지 확인

### 문제 3: Windows에서 `host.docker.internal` 작동 안 함

**해결 방법:**

1. **실제 IP 주소 사용**
   ```bash
   # Windows에서 호스트 IP 확인
   ipconfig
   # IPv4 주소 확인 (예: 192.168.1.100)
   ```

2. **prometheus.yml 수정**
   ```yaml
   static_configs:
     - targets: ['192.168.1.100:8081']  # 실제 IP 주소
   ```

3. **방화벽 확인**
   - Windows 방화벽에서 8081 포트가 허용되어 있는지 확인

## 7. 빠른 테스트 스크립트

다음 스크립트를 실행하여 전체 체인을 테스트할 수 있습니다:

```bash
#!/bin/bash

echo "1. 애플리케이션 Health Check..."
curl -s http://localhost:8081/actuator/health | jq .

echo -e "\n2. Prometheus 메트릭 엔드포인트 확인..."
curl -s http://localhost:8081/actuator/prometheus | head -20

echo -e "\n3. Prometheus 타겟 상태 확인..."
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health, lastError: .lastError}'

echo -e "\n4. Prometheus에서 메트릭 쿼리..."
curl -s 'http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count' | jq '.data.result[0:3]'
```

## 8. 로그 확인

### Prometheus 로그

```bash
docker logs -f <prometheus-container-id>
```

**확인할 내용:**
- 스크랩 에러 메시지
- 타겟 연결 실패 메시지
- 설정 파일 로드 에러

### 애플리케이션 로그

```bash
# 애플리케이션 로그에서 actuator 관련 메시지 확인
# Prometheus 엔드포인트 접근 로그 확인
```

## 9. 대안: Prometheus 설정 수정

만약 `host.docker.internal`이 작동하지 않는다면:

### 옵션 1: Docker 네트워크에 애플리케이션 포함

```yaml
# docker/monitoring-compose.yml에 추가
services:
  commerce-api:
    # ... 애플리케이션 설정
    networks:
      - monitoring

networks:
  monitoring:
    driver: bridge
```

그리고 `prometheus.yml`:
```yaml
static_configs:
  - targets: ['commerce-api:8081']  # 서비스 이름 사용
```

### 옵션 2: extra_hosts 사용

```yaml
# docker/monitoring-compose.yml
services:
  prometheus:
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

## 참고 자료

- [Prometheus 공식 문서](https://prometheus.io/docs/)
- [Spring Boot Actuator 메트릭](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.metrics)
- [Micrometer Prometheus](https://micrometer.io/docs/registry/prometheus)

