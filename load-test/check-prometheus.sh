#!/bin/bash

# Prometheus 데이터 수신 확인 스크립트

echo "=========================================="
echo "Prometheus 데이터 수신 확인 스크립트"
echo "=========================================="
echo ""

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. 애플리케이션 Health Check
echo "1. 애플리케이션 Health Check..."
if curl -s http://localhost:8081/actuator/health > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} Actuator 엔드포인트 접근 가능 (http://localhost:8081/actuator/health)"
    curl -s http://localhost:8081/actuator/health | jq . 2>/dev/null || echo "  (jq가 설치되어 있지 않아 JSON 파싱을 건너뜁니다)"
else
    echo -e "${RED}✗${NC} Actuator 엔드포인트 접근 불가 (http://localhost:8081/actuator/health)"
    echo "  → 애플리케이션이 실행 중인지 확인하세요"
    echo "  → management.server.port 설정을 확인하세요"
fi
echo ""

# 2. Prometheus 메트릭 엔드포인트 확인
echo "2. Prometheus 메트릭 엔드포인트 확인..."
METRICS_RESPONSE=$(curl -s http://localhost:8081/actuator/prometheus 2>&1)
if [ $? -eq 0 ] && [ -n "$METRICS_RESPONSE" ]; then
    echo -e "${GREEN}✓${NC} Prometheus 메트릭 엔드포인트 접근 가능"
    echo "  메트릭 샘플 (처음 5줄):"
    echo "$METRICS_RESPONSE" | head -5 | sed 's/^/  /'
    
    # HTTP 요청 메트릭 확인
    if echo "$METRICS_RESPONSE" | grep -q "http_server_requests"; then
        echo -e "${GREEN}✓${NC} HTTP 요청 메트릭 발견"
        HTTP_COUNT=$(echo "$METRICS_RESPONSE" | grep -c "http_server_requests_seconds_count" || echo "0")
        echo "  → HTTP 요청 메트릭 개수: $HTTP_COUNT"
    else
        echo -e "${YELLOW}⚠${NC} HTTP 요청 메트릭이 없습니다 (아직 요청이 발생하지 않았을 수 있음)"
    fi
else
    echo -e "${RED}✗${NC} Prometheus 메트릭 엔드포인트 접근 불가"
    echo "  → http://localhost:8081/actuator/prometheus 확인"
fi
echo ""

# 3. Prometheus 서비스 확인
echo "3. Prometheus 서비스 확인..."
if curl -s http://localhost:9090/-/healthy > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} Prometheus 서비스 실행 중 (http://localhost:9090)"
else
    echo -e "${RED}✗${NC} Prometheus 서비스 접근 불가"
    echo "  → docker-compose -f docker/monitoring-compose.yml up 실행 확인"
    echo "  → http://localhost:9090 접근 확인"
fi
echo ""

# 4. Prometheus 타겟 상태 확인
echo "4. Prometheus 타겟 상태 확인..."
TARGETS_JSON=$(curl -s http://localhost:9090/api/v1/targets 2>&1)
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓${NC} Prometheus API 접근 가능"
    
    # jq가 있으면 파싱, 없으면 grep으로 간단히 확인
    if command -v jq &> /dev/null; then
        echo "  타겟 상태:"
        echo "$TARGETS_JSON" | jq -r '.data.activeTargets[] | "  - \(.labels.job): \(.health) (lastScrape: \(.lastScrape))"'
        
        # DOWN 상태 타겟 확인
        DOWN_TARGETS=$(echo "$TARGETS_JSON" | jq -r '.data.activeTargets[] | select(.health != "up") | .labels.job')
        if [ -n "$DOWN_TARGETS" ]; then
            echo -e "${RED}✗${NC} DOWN 상태인 타겟:"
            echo "$DOWN_TARGETS" | sed 's/^/  - /'
            echo "  → prometheus.yml의 targets 설정 확인"
            echo "  → 네트워크 연결 확인 (host.docker.internal:8081)"
        else
            echo -e "${GREEN}✓${NC} 모든 타겟이 UP 상태"
        fi
    else
        # jq가 없으면 간단히 확인
        if echo "$TARGETS_JSON" | grep -q '"health":"up"'; then
            echo -e "${GREEN}✓${NC} 타겟이 UP 상태로 보입니다"
        else
            echo -e "${YELLOW}⚠${NC} 타겟 상태 확인 필요 (jq 설치 권장)"
            echo "  → http://localhost:9090/targets 에서 직접 확인하세요"
        fi
    fi
else
    echo -e "${RED}✗${NC} Prometheus API 접근 불가"
fi
echo ""

# 5. Prometheus에서 메트릭 쿼리 테스트
echo "5. Prometheus 메트릭 쿼리 테스트..."
QUERY_RESULT=$(curl -s 'http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count' 2>&1)
if [ $? -eq 0 ]; then
    if command -v jq &> /dev/null; then
        RESULT_COUNT=$(echo "$QUERY_RESULT" | jq '.data.result | length')
        if [ "$RESULT_COUNT" -gt 0 ]; then
            echo -e "${GREEN}✓${NC} 메트릭 데이터 발견 ($RESULT_COUNT 개)"
            echo "  샘플 메트릭:"
            echo "$QUERY_RESULT" | jq -r '.data.result[0:3][] | "  - \(.metric.uri // .metric.job): \(.value[1])"' | head -5
        else
            echo -e "${YELLOW}⚠${NC} 메트릭 데이터가 없습니다"
            echo "  → 애플리케이션에 요청을 보내서 메트릭을 생성하세요"
            echo "  → 예: curl http://localhost:8080/api/v1/products"
        fi
    else
        if echo "$QUERY_RESULT" | grep -q '"result":\[\]'; then
            echo -e "${YELLOW}⚠${NC} 메트릭 데이터가 없습니다"
        else
            echo -e "${GREEN}✓${NC} 메트릭 쿼리 성공 (jq 설치 시 상세 정보 표시 가능)"
        fi
    fi
else
    echo -e "${RED}✗${NC} 메트릭 쿼리 실패"
fi
echo ""

# 6. Docker 컨테이너 확인
echo "6. Docker 컨테이너 상태 확인..."
if command -v docker &> /dev/null; then
    PROMETHEUS_CONTAINER=$(docker ps --filter "ancestor=prom/prometheus" --format "{{.ID}}" | head -1)
    if [ -n "$PROMETHEUS_CONTAINER" ]; then
        echo -e "${GREEN}✓${NC} Prometheus 컨테이너 실행 중 (ID: $PROMETHEUS_CONTAINER)"
        
        # 컨테이너에서 타겟 접근 테스트
        echo "  컨테이너에서 타겟 접근 테스트..."
        if docker exec "$PROMETHEUS_CONTAINER" wget -q -O- http://host.docker.internal:8081/actuator/prometheus > /dev/null 2>&1; then
            echo -e "${GREEN}✓${NC} 컨테이너에서 host.docker.internal:8081 접근 가능"
        else
            echo -e "${RED}✗${NC} 컨테이너에서 host.docker.internal:8081 접근 불가"
            echo "  → Windows: host.docker.internal이 작동하지 않을 수 있음"
            echo "  → prometheus.yml의 targets를 실제 IP로 변경 고려"
        fi
    else
        echo -e "${RED}✗${NC} Prometheus 컨테이너가 실행 중이지 않습니다"
        echo "  → docker-compose -f docker/monitoring-compose.yml up 실행"
    fi
else
    echo -e "${YELLOW}⚠${NC} Docker 명령어를 사용할 수 없습니다"
fi
echo ""

# 요약
echo "=========================================="
echo "진단 완료"
echo "=========================================="
echo ""
echo "다음 단계:"
echo "1. 문제가 발견된 경우: load-test/prometheus-troubleshooting.md 참고"
echo "2. Prometheus UI에서 직접 확인: http://localhost:9090/targets"
echo "3. Grafana 데이터소스 확인: http://localhost:3000 → Configuration → Data Sources"
echo ""

