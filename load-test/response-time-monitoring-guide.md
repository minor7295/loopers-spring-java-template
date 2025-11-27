# 평균 응답 시간 확인 방법

## 1. Locust 부하 테스트 결과 (가장 직접적)

### 웹 UI에서 확인
1. Locust 실행:
   ```bash
   locust -f locustfile.py --host=http://localhost:8080
   ```
2. 브라우저에서 `http://localhost:8089` 접속
3. **Statistics** 탭에서 확인:
   - **Average Response Time**: 평균 응답 시간
   - **Median Response Time**: 중간값 응답 시간
   - **95th percentile**: 95% 요청이 이 시간 이하
   - **99th percentile**: 99% 요청이 이 시간 이하

### CSV 파일에서 확인
부하 테스트 종료 후 생성되는 CSV 파일:
- `locust_stats.csv`: 통계 요약
- `locust_failures.csv`: 실패한 요청

**예시:**
```csv
Type,Name,Request Count,Failure Count,Median Response Time,Average Response Time,Min Response Time,Max Response Time,Requests/s,Failures/s,50%,66%,75%,80%,90%,95%,98%,99%,99.9%,99.99%,100%
GET,/api/v1/products,30723,9267,18000.0,17833.31925543083,1.3985999994474696,46166.81850000077,153.69082080590806,46.35786988277024,18000,28000,29000,30000,37000,39000,40000,41000,44000,46000,46000
```

**주요 컬럼:**
- `Average Response Time`: 평균 응답 시간 (밀리초)
- `Median Response Time`: 중간값 응답 시간
- `95%`: 95th percentile

### 헤드리스 모드에서 확인
```bash
locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 5m --csv=results
```

테스트 종료 후 콘솔에 통계가 출력됩니다.

## 2. Prometheus 메트릭 (실시간 모니터링)

### 메트릭 확인
```bash
# Prometheus 엔드포인트에서 확인
curl http://localhost:8081/actuator/prometheus | grep http_server_requests_seconds
```

### 주요 메트릭
Spring Boot Actuator는 자동으로 HTTP 요청 응답 시간을 측정합니다:

- `http_server_requests_seconds_count`: 요청 수
- `http_server_requests_seconds_sum`: 총 응답 시간 (초)
- `http_server_requests_seconds_max`: 최대 응답 시간
- `http_server_requests_seconds{quantile="0.5"}`: 중간값 (50th percentile)
- `http_server_requests_seconds{quantile="0.95"}`: 95th percentile
- `http_server_requests_seconds{quantile="0.99"}`: 99th percentile

### 평균 응답 시간 계산 (PromQL)
```promql
# 전체 평균 응답 시간
rate(http_server_requests_seconds_sum[5m]) / rate(http_server_requests_seconds_count[5m])

# 특정 엔드포인트 평균 응답 시간
rate(http_server_requests_seconds_sum{uri="/api/v1/products"}[5m]) / 
rate(http_server_requests_seconds_count{uri="/api/v1/products"}[5m])

# 특정 HTTP 메서드 평균 응답 시간
rate(http_server_requests_seconds_sum{method="GET"}[5m]) / 
rate(http_server_requests_seconds_count{method="GET"}[5m])
```

### 예시 출력
```
http_server_requests_seconds_count{application="commerce-api",method="GET",status="200",uri="/api/v1/products"} 30723.0
http_server_requests_seconds_sum{application="commerce-api",method="GET",status="200",uri="/api/v1/products"} 548234.5
http_server_requests_seconds_max{application="commerce-api",method="GET",status="200",uri="/api/v1/products"} 46.166
```

**평균 계산:**
```
평균 응답 시간 = sum / count
              = 548234.5 / 30723
              = 약 17.83초
```

## 3. Grafana 대시보드 (시각화)

### Prometheus에서 직접 쿼리
1. `http://localhost:9090` 접속
2. 쿼리 입력:
   ```promql
   rate(http_server_requests_seconds_sum{uri="/api/v1/products"}[5m]) / 
   rate(http_server_requests_seconds_count{uri="/api/v1/products"}[5m])
   ```
3. **Execute** 클릭

### Grafana 대시보드 생성
**패널 추가:**
1. **Add panel** → **Time series**
2. 쿼리 입력:
   ```promql
   rate(http_server_requests_seconds_sum{uri=~"/api/v1/.*"}[5m]) / 
   rate(http_server_requests_seconds_count{uri=~"/api/v1/.*"}[5m])
   ```
3. **Unit**: `seconds` 또는 `milliseconds`
4. **Legend**: `{{uri}} - Average`

**Percentile 패널:**
```promql
# 50th percentile (중간값)
histogram_quantile(0.50, 
  rate(http_server_requests_seconds_bucket{uri="/api/v1/products"}[5m]))

# 95th percentile
histogram_quantile(0.95, 
  rate(http_server_requests_seconds_bucket{uri="/api/v1/products"}[5m]))

# 99th percentile
histogram_quantile(0.99, 
  rate(http_server_requests_requests_seconds_bucket{uri="/api/v1/products"}[5m]))
```

## 4. Actuator Metrics 엔드포인트

### JSON 형식으로 확인
```bash
curl http://localhost:8081/actuator/metrics/http.server.requests
```

**응답 예시:**
```json
{
  "name": "http.server.requests",
  "description": "HTTP server requests",
  "baseUnit": "seconds",
  "measurements": [
    {
      "statistic": "COUNT",
      "value": 30723.0
    },
    {
      "statistic": "TOTAL_TIME",
      "value": 548234.5
    },
    {
      "statistic": "MAX",
      "value": 46.166
    }
  ],
  "availableTags": [
    {
      "tag": "uri",
      "values": ["/api/v1/products", "/api/v1/products/{id}"]
    },
    {
      "tag": "method",
      "values": ["GET"]
    },
    {
      "tag": "status",
      "values": ["200", "500"]
    }
  ]
}
```

### 특정 태그로 필터링
```bash
# 특정 엔드포인트만
curl "http://localhost:8081/actuator/metrics/http.server.requests?tag=uri:/api/v1/products"

# 특정 상태 코드만
curl "http://localhost:8081/actuator/metrics/http.server.requests?tag=status:200"
```

## 5. 애플리케이션 로그 (선택적)

### 로깅 설정 추가
`application.yml`에 추가:
```yaml
logging:
  level:
    org.springframework.web: DEBUG
```

또는 커스텀 인터셉터로 응답 시간 로깅:
```java
@Component
public class ResponseTimeInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("startTime", System.currentTimeMillis());
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        long startTime = (Long) request.getAttribute("startTime");
        long duration = System.currentTimeMillis() - startTime;
        log.info("Request {} took {}ms", request.getRequestURI(), duration);
    }
}
```

## 6. 실시간 모니터링 스크립트

### PowerShell 스크립트
```powershell
# response-time-check.ps1
while ($true) {
    $response = Invoke-WebRequest -Uri "http://localhost:8081/actuator/metrics/http.server.requests?tag=uri:/api/v1/products" -UseBasicParsing
    $data = $response.Content | ConvertFrom-Json
    
    $count = ($data.measurements | Where-Object { $_.statistic -eq "COUNT" }).value
    $totalTime = ($data.measurements | Where-Object { $_.statistic -eq "TOTAL_TIME" }).value
    
    if ($count -gt 0) {
        $avgTime = ($totalTime / $count) * 1000  # 초를 밀리초로 변환
        Write-Host "$(Get-Date -Format 'HH:mm:ss') - Average Response Time: $([math]::Round($avgTime, 2))ms (Count: $count)"
    }
    
    Start-Sleep -Seconds 5
}
```

## 요약: 가장 빠른 확인 방법

### 1. Locust 웹 UI (부하 테스트 중)
- 가장 직관적
- 실시간 통계 확인
- URL: `http://localhost:8089`

### 2. Prometheus 쿼리 (실시간)
```promql
rate(http_server_requests_seconds_sum{uri="/api/v1/products"}[5m]) / 
rate(http_server_requests_seconds_count{uri="/api/v1/products"}[5m])
```
- URL: `http://localhost:9090`

### 3. Actuator 엔드포인트 (간단 확인)
```bash
curl http://localhost:8081/actuator/metrics/http.server.requests
```
- 수동 계산 필요 (TOTAL_TIME / COUNT)

## 참고: 응답 시간 단위

- **Prometheus**: 초 (seconds)
- **Locust**: 밀리초 (milliseconds)
- **변환**: 1초 = 1000밀리초

**예시:**
- Prometheus: `17.833` 초
- Locust: `17833` 밀리초

