# Docker Compose 설정 가이드

## 모니터링 스택 실행

### 1. Prometheus & Grafana 실행

```bash
cd docker
docker-compose -f monitoring-compose.yml up -d
```

### 2. Grafana 접속

- URL: http://localhost:3000
- 사용자명: `admin`
- 비밀번호: `admin`

### 3. 대시보드 확인

Grafana에 접속하면 자동으로 다음 대시보드가 로드됩니다:
- **Resilience4j Circuit Breaker & Bulkhead Dashboard**: Circuit Breaker, Bulkhead, Retry 메트릭 모니터링

### 4. Circuit Breaker 테스트 실행

애플리케이션을 실행한 후 다음 테스트를 실행하여 Circuit Breaker를 OPEN 상태로 만듭니다:

```bash
./gradlew :apps:commerce-api:test --tests CircuitBreakerLoadTest
```

또는 IDE에서 `CircuitBreakerLoadTest` 클래스를 실행합니다.

### 5. Grafana에서 모니터링

테스트 실행 후 Grafana 대시보드에서 다음을 확인할 수 있습니다:
- Circuit Breaker 상태 변화 (CLOSED → OPEN)
- 실패율 증가
- Not Permitted Calls (Circuit Open 상태에서 차단된 호출)
- Bulkhead 사용률
- Retry 시도 횟수

## Prometheus 설정

Prometheus는 `docker/grafana/prometheus.yml`에서 설정됩니다.

기본 설정:
- 스크랩 간격: 5초
- 타겟: `host.docker.internal:8081` (Spring Boot Actuator Prometheus 엔드포인트)

애플리케이션이 다른 포트에서 실행되는 경우 `prometheus.yml`을 수정하세요.

## 문제 해결

### Grafana에서 메트릭이 보이지 않는 경우

1. Prometheus가 정상적으로 실행 중인지 확인:
   ```bash
   docker ps | grep prometheus
   ```

2. Prometheus UI에서 타겟 상태 확인:
   - URL: http://localhost:9090
   - Status → Targets 메뉴에서 `spring-boot-app` 타겟이 UP 상태인지 확인

3. 애플리케이션이 실행 중이고 Actuator 엔드포인트가 활성화되어 있는지 확인:
   ```bash
   curl http://localhost:8081/actuator/prometheus
   ```

4. Prometheus가 애플리케이션 메트릭을 수집하고 있는지 확인:
   - Prometheus UI에서 다음 쿼리 실행:
   ```
   resilience4j_circuitbreaker_state{name="paymentGatewayClient"}
   ```

### 대시보드가 로드되지 않는 경우

1. Grafana 로그 확인:
   ```bash
   docker logs docker-grafana-1
   ```

2. 대시보드 파일 경로 확인:
   - `docker/grafana/dashboards/resilience4j-circuit-breaker.json` 파일이 존재하는지 확인
   - `docker/grafana/provisioning/dashboards/dashboard.yml` 파일이 올바르게 설정되어 있는지 확인

3. Grafana 재시작:
   ```bash
   docker-compose -f monitoring-compose.yml restart grafana
   ```

