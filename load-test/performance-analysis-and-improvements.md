# 부하 테스트 결과 분석 및 개선 전략

## 📊 테스트 결과 요약

### 주요 지표

#### 1. 상품 목록 조회 (`/api/v1/products`)
- **Request Count**: 30,723
- **Failure Count**: 9,267 (약 **30% 실패율** ⚠️)
- **Median Response Time**: 18,000ms (**18초** ⚠️)
- **Average Response Time**: 17,833ms
- **95th Percentile**: 39,000ms (**39초** ⚠️)
- **Requests/s**: 153.69
- **Failures/s**: 46.36

#### 2. 상품 상세 조회 (`/api/v1/products/{id}`)
- **대부분 1회씩만 조회** (캐시 효과 없음)
- **응답 시간**: 3초 ~ 46초 (매우 불안정)
- **실패 유형**:
  - `ConnectionRefusedError`: 서버 연결 거부
  - `HTTP 500`: 서버 내부 오류

## 🔍 문제점 분석

### 1. 높은 실패율 (30%)
- **원인**: 서버 과부하로 인한 연결 거부 및 타임아웃
- **영향**: 사용자 경험 저하, 비즈니스 손실

### 2. 매우 느린 응답 시간 (평균 18초)
- **원인**:
  - DB 연결 풀 고갈
  - 캐시 미스로 인한 DB 조회 증가
  - 인덱스 미활용 가능성
- **영향**: 사용자 이탈률 증가

### 3. 캐시 효과 부족
- **상품 목록**: 첫 페이지만 캐시 (두 번째 페이지는 DB 조회)
- **상품 상세**: 각기 다른 ID로 1회씩만 조회되어 캐시 히트율 낮음
- **영향**: DB 부하 증가

### 4. 서버 리소스 제한
- **Redis**: CPU 1%, 메모리 64MB (매우 제한적)
- **MySQL**: CPU 5%, 메모리 256MB, max-connections=30
- **영향**: 캐시 저장/조회 실패, DB 연결 풀 고갈

## 🎯 개선 전략

### 1. 캐시 전략 개선 (우선순위: 높음)

#### 1.1 상품 목록 캐시 확장
**현재**: 첫 페이지(page=0)만 캐시
**개선**: 상위 3-5페이지까지 캐시 확장

```java
// ProductCacheService.java 수정
public ProductInfoList getCachedProductList(Long brandId, String sort, int page, int size) {
    // 상위 5페이지까지 캐시
    if (page > 4) {  // 기존: page != 0
        return null;
    }
    // ... 나머지 코드 동일
}

public void cacheProductList(Long brandId, String sort, int page, int size, ProductInfoList productInfoList) {
    // 상위 5페이지까지 캐시
    if (page > 4) {  // 기존: page != 0
        return;
    }
    // ... 나머지 코드 동일
}
```

**예상 효과**:
- 캐시 히트율 증가 (약 60-80% → 80-90%)
- DB 부하 감소
- 응답 시간 개선 (18초 → 1-2초)

#### 1.2 캐시 TTL 조정
**현재**: 5분
**개선**: 10-15분으로 증가 (상품 정보 변경 빈도가 낮다면)

```java
private static final Duration CACHE_TTL = Duration.ofMinutes(10); // 5분 → 10분
```

**예상 효과**:
- 캐시 유지 시간 증가
- DB 조회 빈도 감소

#### 1.3 상품 상세 캐시 워밍업
**현재**: 요청 시에만 캐시 저장
**개선**: 인기 상품 미리 캐시 (선택적)

```java
// 인기 상품 ID 목록을 미리 캐시에 로드
@PostConstruct
public void warmupCache() {
    // 인기 상품 상위 100개 미리 캐시
    List<Long> popularProductIds = productRepository.findPopularProducts(100);
    // ... 캐시 저장
}
```

### 2. 인덱스 최적화 (우선순위: 중간)

#### 2.1 인덱스 사용 확인
**현재**: 인덱스는 존재하지만 실제 사용 여부 확인 필요

```sql
-- 쿼리 실행 계획 확인
EXPLAIN SELECT p.* FROM product p 
WHERE p.ref_brand_id IS NULL 
ORDER BY p.created_at DESC 
LIMIT 20 OFFSET 0;

-- 인덱스 사용 여부 확인
SHOW INDEX FROM product;
```

**개선 사항**:
- `created_at` 정렬 시 `idx_product_brand_created` 또는 `idx_product_created` 사용 확인
- 인덱스가 사용되지 않으면 쿼리 최적화 필요

#### 2.2 최신순 정렬 인덱스 추가 (필요 시)
**현재**: `created_at` 단독 인덱스 없음 (브랜드 필터용만 존재)

```sql
-- 전체 조회 + 최신순 정렬 최적화
CREATE INDEX IF NOT EXISTS idx_product_created 
ON product(created_at DESC);
```

**예상 효과**:
- 최신순 정렬 성능 개선
- 파일 정렬 제거

### 3. DB 연결 풀 최적화 (우선순위: 높음)

#### 3.1 연결 풀 크기 조정
**현재**: 
- HikariCP: `maximum-pool-size: 40`
- MySQL: `max-connections: 30`

**개선**: HikariCP를 MySQL max-connections보다 작게 설정

```yaml
# modules/jpa/src/main/resources/jpa.yml
datasource:
  mysql-jpa:
    main:
      maximum-pool-size: 25  # MySQL max-connections=30보다 작게
      minimum-idle: 10
      connection-timeout: 5000  # 3초 → 5초로 증가
```

#### 3.2 MySQL max-connections 증가 (선택적)
**현재**: `max-connections=30`
**개선**: 리소스 여유 시 50-100으로 증가

```yaml
# docker/infra-compose.yml
mysql:
  command: 
    - --max-connections=50  # 30 → 50
```

### 4. Redis 리소스 증가 (우선순위: 중간)

#### 4.1 Redis 리소스 제한 완화
**현재**: CPU 1%, 메모리 64MB
**개선**: CPU 5%, 메모리 256MB

```yaml
# docker/infra-compose.yml
redis-master:
  deploy:
    resources:
      limits:
        cpus: '0.05'  # 0.01 → 0.05
        memory: 256M  # 64M → 256M
      reservations:
        cpus: '0.05'
        memory: 192M  # 48M → 192M
```

**예상 효과**:
- 캐시 저장/조회 성능 개선
- 캐시 실패율 감소

### 5. 애플리케이션 레벨 개선 (우선순위: 중간)

#### 5.1 Circuit Breaker 패턴 적용
**목적**: DB/Redis 장애 시 빠른 실패 처리

```java
@CircuitBreaker(name = "productService", fallbackMethod = "getProductsFallback")
public ProductInfoList getProducts(...) {
    // ...
}
```

#### 5.2 비동기 캐시 저장
**목적**: 캐시 저장 실패가 메인 로직에 영향 없도록

```java
@Async
public void cacheProductList(...) {
    // 비동기로 캐시 저장
}
```

## 📈 예상 개선 효과

### 단기 개선 (캐시 전략 개선)
- **응답 시간**: 18초 → 2-3초 (약 85% 개선)
- **실패율**: 30% → 5-10% (약 70% 개선)
- **캐시 히트율**: 20-30% → 70-80%

### 중기 개선 (인덱스 + 리소스 증가)
- **응답 시간**: 2-3초 → 0.5-1초 (약 95% 개선)
- **실패율**: 5-10% → 1-2%
- **처리량**: 153 req/s → 500+ req/s

## 🚀 구현 우선순위

1. **즉시 적용** (1-2일)
   - [ ] 상품 목록 캐시 확장 (상위 5페이지)
   - [ ] DB 연결 풀 크기 조정
   - [ ] 캐시 TTL 증가 (10분)

2. **단기 적용** (1주)
   - [ ] 인덱스 사용 확인 및 최적화
   - [ ] Redis 리소스 증가
   - [ ] MySQL max-connections 조정

3. **중기 적용** (2-4주)
   - [ ] Circuit Breaker 패턴
   - [ ] 비동기 캐시 저장
   - [ ] 캐시 워밍업 전략

## 📝 모니터링 지표

개선 후 다음 지표를 모니터링:
- **응답 시간**: 평균, 95th percentile
- **실패율**: 전체 실패율, 오류 유형별 분류
- **캐시 히트율**: Redis 캐시 히트/미스 비율
- **DB 연결 풀 사용률**: active/idle/waiting 연결 수
- **Redis 메모리 사용률**: 메모리 사용량 및 eviction 발생 여부

