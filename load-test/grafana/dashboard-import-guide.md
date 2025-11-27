# Grafana 대시보드 가이드

## 대시보드 임포트 방법

### 1. Grafana 접속
```
http://localhost:3000
```
- 기본 계정: `admin` / `admin`

### 2. 대시보드 임포트
1. Grafana 메인 화면에서 **"+"** 버튼 클릭
2. **"Import dashboard"** 선택
3. **"Upload JSON file"** 클릭하여 `product-cache-dashboard.json` 파일 선택
   또는 JSON 내용을 직접 붙여넣기
4. **"Load"** 버튼 클릭
5. **Prometheus 데이터소스 선택** (없으면 먼저 설정 필요)
6. **"Import"** 버튼 클릭

### 3. Prometheus 데이터소스 설정 (필요 시)

1. **Configuration** → **Data sources** → **Add data source**
2. **Prometheus** 선택
3. 설정:
   - **URL**: `http://prometheus:9090` (Docker Compose 사용 시)
     또는 `http://localhost:9090` (로컬 실행 시)
4. **Save & test** 클릭

## 대시보드 패널 설명

### 1. Cache Hits vs Misses (Rate)
- 캐시 히트와 미스의 초당 발생률
- 상품 목록(list)과 상세(detail) 구분
- **목표**: 히트가 미스보다 높게 유지

### 2. Overall Cache Hit Rate (%)
- 전체 캐시 히트율 (게이지)
- **목표**: 70% 이상 (녹색)
- **경고**: 50% 미만 (노란색)
- **위험**: 30% 미만 (빨간색)

### 3. Cache Hit Rate by Operation Type (%)
- 상품 목록과 상세별 캐시 히트율 추이
- 시간에 따른 변화 확인 가능

### 4. Cache Miss Reasons (Rate)
- 캐시 미스 원인 분석
  - **Page Not Zero**: 첫 페이지가 아닌 경우 (정상)
  - **Not Found**: 캐시에 없는 경우 (워밍업 필요)

### 5. Cache Save Operations (Rate)
- 캐시 저장 성공/실패율
- Redis 리소스 부족 시 실패 증가

### 6. Cache Save Failure Rate (%)
- 캐시 저장 실패율
- **목표**: 1% 미만
- **경고**: 1-5%
- **위험**: 5% 이상

### 7. Cache Query Failures (Rate)
- 캐시 조회 실패율
- Redis 연결 문제 시 증가

### 8. Cache Query Failure Rate (%)
- 캐시 조회 실패율
- **목표**: 0.5% 미만
- **경고**: 0.5-1%
- **위험**: 1% 이상

### 9. Cache Statistics (Total Counts)
- 전체 통계 테이블
- 누적 히트/미스/저장/실패 수

## 사용 팁

### 부하 테스트 중 모니터링
1. 대시보드를 열어두고 실시간으로 확인
2. **Refresh**: 10초마다 자동 갱신
3. **Time range**: 기본 15분, 필요시 조정

### 문제 진단
- **캐시 히트율이 낮은 경우**:
  - "Cache Miss Reasons" 패널에서 "Not Found" 비율 확인
  - 캐시 워밍업 필요 또는 TTL 증가 검토

- **캐시 저장 실패가 많은 경우**:
  - "Cache Save Failure Rate" 확인
  - Redis 리소스 부족 가능성

- **캐시 조회 실패가 많은 경우**:
  - "Cache Query Failure Rate" 확인
  - Redis 연결 상태 확인

## 대시보드 커스터마이징

### 시간 범위 변경
- 우측 상단의 시간 선택기에서 변경
- 부하 테스트 기간에 맞게 조정

### 새 패널 추가
1. 대시보드 우측 상단 **"Add"** → **"Visualization"**
2. Prometheus 쿼리 입력
3. 패널 설정 조정

### 알림 설정 (선택)
1. 패널에서 **"Edit"** → **"Alert"** 탭
2. 임계값 설정
3. 알림 규칙 생성

## 참고

- Prometheus 엔드포인트: `http://localhost:8081/actuator/prometheus`
- Grafana: `http://localhost:3000`
- 대시보드 UID: `product-cache-dashboard`

