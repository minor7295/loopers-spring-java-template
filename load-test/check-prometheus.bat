@echo off
REM Prometheus 데이터 수신 확인 스크립트 (Windows)

echo ==========================================
echo Prometheus 데이터 수신 확인 스크립트
echo ==========================================
echo.

REM 1. 애플리케이션 Health Check
echo 1. 애플리케이션 Health Check...
curl -s http://localhost:8081/actuator/health >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Actuator 엔드포인트 접근 가능
    curl -s http://localhost:8081/actuator/health
) else (
    echo [ERROR] Actuator 엔드포인트 접근 불가
    echo   - 애플리케이션이 실행 중인지 확인하세요
    echo   - management.server.port 설정을 확인하세요
)
echo.
echo.

REM 2. Prometheus 메트릭 엔드포인트 확인
echo 2. Prometheus 메트릭 엔드포인트 확인...
curl -s http://localhost:8081/actuator/prometheus >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Prometheus 메트릭 엔드포인트 접근 가능
    echo   메트릭 샘플:
    curl -s http://localhost:8081/actuator/prometheus | findstr /C:"http_server_requests" | findstr /N "^" | more +1 | more +1 | more +1 | more +1 | more +1
) else (
    echo [ERROR] Prometheus 메트릭 엔드포인트 접근 불가
)
echo.
echo.

REM 3. Prometheus 서비스 확인
echo 3. Prometheus 서비스 확인...
curl -s http://localhost:9090/-/healthy >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Prometheus 서비스 실행 중
) else (
    echo [ERROR] Prometheus 서비스 접근 불가
    echo   - docker-compose -f docker\monitoring-compose.yml up 실행 확인
)
echo.
echo.

REM 4. Prometheus 타겟 상태 확인
echo 4. Prometheus 타겟 상태 확인...
curl -s http://localhost:9090/api/v1/targets >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Prometheus API 접근 가능
    echo   타겟 상태 확인:
    curl -s http://localhost:9090/api/v1/targets | findstr /C:"health"
    echo   - 자세한 내용은 http://localhost:9090/targets 에서 확인하세요
) else (
    echo [ERROR] Prometheus API 접근 불가
)
echo.
echo.

REM 5. Prometheus에서 메트릭 쿼리 테스트
echo 5. Prometheus 메트릭 쿼리 테스트...
curl -s "http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count" >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] 메트릭 쿼리 성공
    echo   - 자세한 내용은 http://localhost:9090/graph 에서 확인하세요
) else (
    echo [ERROR] 메트릭 쿼리 실패
)
echo.
echo.

REM 6. Docker 컨테이너 확인
echo 6. Docker 컨테이너 상태 확인...
docker ps --filter "ancestor=prom/prometheus" --format "{{.ID}}" >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Prometheus 컨테이너 실행 중
    for /f "tokens=*" %%i in ('docker ps --filter "ancestor=prom/prometheus" --format "{{.ID}}"') do set PROMETHEUS_CONTAINER=%%i
    echo   컨테이너 ID: %PROMETHEUS_CONTAINER%
) else (
    echo [ERROR] Prometheus 컨테이너가 실행 중이지 않습니다
    echo   - docker-compose -f docker\monitoring-compose.yml up 실행
)
echo.
echo.

echo ==========================================
echo 진단 완료
echo ==========================================
echo.
echo 다음 단계:
echo 1. 문제가 발견된 경우: load-test\prometheus-troubleshooting.md 참고
echo 2. Prometheus UI에서 직접 확인: http://localhost:9090/targets
echo 3. Grafana 데이터소스 확인: http://localhost:3000 -^> Configuration -^> Data Sources
echo.

pause

