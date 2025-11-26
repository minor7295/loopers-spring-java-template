# 캐시 구현 방식 선택 가이드

이 문서는 프로젝트에서 **로컬 캐시**와 **글로벌 캐시**를 구현할 때, `@Cacheable`과 `RedisTemplate` 중 어떤 방식을 선택해야 하는지에 대한 명확한 기준을 제공합니다.

## 목차

1. [개요](#개요)
2. [구현 방식 비교](#구현-방식-비교)
3. [선택 기준](#선택-기준)
4. [현재 프로젝트 적용 사례](#현재-프로젝트-적용-사례)
5. [결정 트리](#결정-트리)
6. [모범 사례](#모범-사례)

---

## 개요

프로젝트에서는 두 가지 캐시 구현 방식을 사용합니다:

1. **로컬 캐시**: `@Cacheable` + Caffeine (인스턴스 메모리 캐시)
2. **글로벌 캐시**: `RedisTemplate` (분산 캐시)

각 방식은 서로 다른 목적과 특성을 가지므로, 적절한 선택 기준이 필요합니다.

---

## 구현 방식 비교

### 1. @Cacheable (로컬 캐시)

**특징:**
- Spring Cache 추상화 기반
- Caffeine을 백엔드로 사용 (인스턴스 메모리)
- 선언적 프로그래밍 (어노테이션 기반)
- 간단한 설정과 사용

**장점:**
- ✅ 구현이 매우 간단 (`@Cacheable` 어노테이션만 추가)
- ✅ 코드 가독성 우수 (비즈니스 로직에 집중)
- ✅ 네트워크 hop 없음 (인메모리 접근)
- ✅ 매우 빠른 응답 속도 (마이크로초 단위)
- ✅ Spring 프레임워크와 완벽 통합

**단점:**
- ❌ 인스턴스 간 캐시 공유 불가 (각 인스턴스마다 별도 캐시)
- ❌ 인스턴스 메모리 제약 (캐시 크기 제한)
- ❌ 복잡한 무효화 로직 구현 어려움
- ❌ 캐시 통계/모니터링이 제한적

**적용 예시:**
```java
@Cacheable(cacheNames = "userInfo", key = "#userId")
public UserInfo getUserInfo(String userId) {
    User user = userRepository.findByUserId(userId);
    return UserInfo.from(user);
}
```

---

### 2. RedisTemplate (글로벌 캐시)

**특징:**
- Redis를 백엔드로 사용 (분산 캐시)
- 명령형 프로그래밍 (수동 캐시 관리)
- 세밀한 제어 가능

**장점:**
- ✅ 여러 인스턴스 간 캐시 공유 (일관성 보장)
- ✅ 대용량 데이터 저장 가능 (메모리 제약 적음)
- ✅ 복잡한 무효화 로직 구현 가능 (Lua 스크립트, 패턴 매칭)
- ✅ 다양한 Redis 기능 활용 (Sorted Set, Pub/Sub 등)
- ✅ 상세한 모니터링 및 통계 수집 가능

**단점:**
- ❌ 네트워크 지연 (Redis 서버 통신)
- ❌ 구현 복잡도 증가 (수동 캐시 관리)
- ❌ Redis 장애 시 영향 (폴백 처리 필요)
- ❌ 코드 가독성 저하 (비즈니스 로직과 캐시 로직 혼재)

**적용 예시:**
```java
public ProductInfo getProduct(Long productId) {
    // 1. 캐시 확인
    ProductInfo cached = productCacheService.getCachedProduct(productId);
    if (cached != null) {
        return cached;
    }
    
    // 2. DB 조회
    Product product = productRepository.findById(productId);
    ProductInfo result = ProductInfo.from(product);
    
    // 3. 캐시 저장
    productCacheService.cacheProduct(productId, result);
    return result;
}
```

---

## 선택 기준

### 로컬 캐시 (@Cacheable)를 선택해야 하는 경우

#### ✅ 1. 인스턴스 간 일관성이 크게 중요하지 않음
- **예시**: 사용자 정보 조회, 마스터 데이터 (브랜드, 코드 테이블)
- **이유**: 각 인스턴스에서 약간 다른 캐시 값을 가져도 비즈니스적으로 문제없음
- **TTL**: 짧은 TTL(수 초~수 분)로 최신성 보장

#### ✅ 2. 매우 빠른 응답 속도가 필요함
- **예시**: 인증/인가 정보, 세션 데이터
- **이유**: 네트워크 hop 제거로 마이크로초 단위 응답 가능
- **성능**: 로컬 캐시 > Redis 캐시 > DB 조회

#### ✅ 3. 데이터 변경 빈도가 낮음
- **예시**: 마스터 데이터, 설정 데이터, 코드 테이블
- **이유**: 자주 변경되지 않아 TTL 기반 무효화로 충분
- **무효화**: `@CacheEvict` 또는 TTL만으로 관리 가능

#### ✅ 4. 캐시 키 수가 제한적이고 메모리 부담이 적음
- **예시**: 사용자 정보 (최대 10,000명), 브랜드 목록 (수십 개)
- **이유**: 인스턴스 메모리에 안전하게 저장 가능
- **제한**: Caffeine `maximumSize` 설정으로 메모리 제어

#### ✅ 5. 단순한 캐시 로직
- **예시**: 단일 키 조회, 단순 TTL 기반 만료
- **이유**: 복잡한 패턴 매칭이나 Lua 스크립트 불필요

---

### 글로벌 캐시 (RedisTemplate)를 선택해야 하는 경우

#### ✅ 1. 여러 인스턴스에서 동일한 캐시 공유 필요
- **예시**: 상품 목록, 상품 상세 (모든 인스턴스에서 동일한 데이터)
- **이유**: 사용자가 어떤 인스턴스로 요청해도 같은 결과를 봐야 함
- **일관성**: 분산 환경에서 강한 일관성 보장

#### ✅ 2. 복잡한 무효화 로직 필요
- **예시**: 상품 업데이트 시 브랜드별/전체 목록 캐시 무효화
- **이유**: 패턴 매칭, Lua 스크립트 등 고급 기능 필요
- **구현**: `ProductCacheService`의 Lua 스크립트 기반 무효화

#### ✅ 3. 캐시 키 수가 많고 인스턴스 메모리로 감당 어려움
- **예시**: 상품 목록 (수만~수십만 개), 동적 쿼리 결과
- **이유**: Redis의 대용량 메모리 활용
- **확장성**: 수평 확장 가능

#### ✅ 4. 트래픽이 큰 핵심 도메인
- **예시**: 상품 조회 (전체 트래픽의 대부분)
- **이유**: 캐시 히트율이 전체 성능에 큰 영향
- **최적화**: 세밀한 캐시 전략 필요 (예: 첫 페이지만 캐시)

#### ✅ 5. Redis의 고급 기능 활용 필요
- **예시**: Sorted Set (랭킹), Pub/Sub (캐시 무효화 알림)
- **이유**: 단순 키-값 저장 이상의 기능 필요

---

## 현재 프로젝트 적용 사례

### ✅ 로컬 캐시 (@Cacheable) 적용

#### 1. 사용자 정보 조회
- **위치**: `UserInfoFacade.getUserInfo()`
- **이유**: 
  - 인스턴스 간 일관성 크게 중요하지 않음 (사용자별로 조회)
  - 변경 빈도 낮음 (이메일, 생년월일 등)
  - 빠른 응답 필요 (인증/인가 후 자주 조회)
- **설정**: TTL 5분, 최대 10,000 엔트리

```java
@Cacheable(cacheNames = "userInfo", key = "#userId")
public UserInfo getUserInfo(String userId) {
    // ...
}
```

---

### ✅ 글로벌 캐시 (RedisTemplate) 적용

#### 1. 상품 목록 조회
- **위치**: `CatalogProductFacade.getProducts()`, `ProductCacheService`
- **이유**:
  - 모든 인스턴스에서 동일한 상품 목록 제공 필요
  - 복잡한 무효화 로직 (브랜드별, 전체 목록 무효화)
  - 대용량 데이터 (수만 개 상품)
  - 트래픽이 큰 핵심 도메인
- **최적화**: 첫 페이지(page=0)만 캐시하여 메모리 효율화

#### 2. 상품 상세 조회
- **위치**: `CatalogProductFacade.getProduct()`, `ProductCacheService`
- **이유**:
  - 모든 인스턴스에서 동일한 상품 정보 제공 필요
  - 상품 업데이트 시 캐시 무효화 필요
  - 트래픽이 큰 핵심 도메인

#### 3. 주문 정보 조회
- **위치**: `OrderCacheService`
- **이유**:
  - 여러 인스턴스에서 동일한 주문 정보 제공 필요
  - 주문 상태 변경 시 캐시 무효화 필요

---

## 결정 트리

```
새로운 캐시를 추가해야 할 때:

1. 여러 인스턴스에서 동일한 캐시 공유가 필요한가?
   ├─ YES → RedisTemplate (글로벌 캐시)
   │         └─ 복잡한 무효화 로직이 필요한가?
   │            ├─ YES → RedisTemplate + Lua 스크립트
   │            └─ NO → RedisTemplate (단순 무효화)
   │
   └─ NO → 다음 질문으로

2. 매우 빠른 응답 속도가 필요한가? (마이크로초 단위)
   ├─ YES → @Cacheable (로컬 캐시)
   │         └─ 데이터 변경 빈도가 낮은가?
   │            ├─ YES → @Cacheable + TTL
   │            └─ NO → @Cacheable + @CacheEvict
   │
   └─ NO → 다음 질문으로

3. 캐시 키 수가 많고 인스턴스 메모리로 감당 어려운가?
   ├─ YES → RedisTemplate (글로벌 캐시)
   └─ NO → 다음 질문으로

4. 복잡한 캐시 로직이 필요한가? (패턴 매칭, Lua 스크립트 등)
   ├─ YES → RedisTemplate (글로벌 캐시)
   └─ NO → @Cacheable (로컬 캐시)
```

---

## 모범 사례

### 1. 하이브리드 캐시 전략 (2-Tier Cache)

글로벌 캐시 위에 로컬 캐시를 추가하여 최적의 성능을 얻을 수 있습니다.

**구조:**
```
요청 → 로컬 캐시(@Cacheable) → 글로벌 캐시(Redis) → DB
```

**적용 시기:**
- 트래픽이 매우 큰 핵심 API
- 네트워크 지연을 최소화하고 싶을 때
- 로컬 캐시로 Redis 부하 감소가 필요할 때

**주의사항:**
- 캐시 일관성 관리 복잡도 증가
- 무효화 로직이 복잡해짐 (로컬 + 글로벌 모두 무효화)

---

### 2. 캐시 무효화 전략

#### 로컬 캐시 (@Cacheable)
```java
// TTL 기반 자동 만료 (가장 간단)
@Cacheable(cacheNames = "userInfo", key = "#userId")

// 명시적 무효화
@CacheEvict(cacheNames = "userInfo", key = "#userId")
public void updateUserInfo(String userId) {
    // ...
}
```

#### 글로벌 캐시 (RedisTemplate)
```java
// 단일 키 무효화
redisTemplate.delete("product:detail:123");

// 패턴 매칭 무효화 (Lua 스크립트 사용)
evictByPatternScript.execute(keys, "product:list:*");
```

---

### 3. 에러 처리

#### 로컬 캐시 (@Cacheable)
- 캐시 실패 시 자동으로 메서드 실행 (폴백)
- 추가 에러 처리 불필요

#### 글로벌 캐시 (RedisTemplate)
```java
public ProductInfo getCachedProduct(Long productId) {
    try {
        String cachedValue = redisTemplate.opsForValue().get(key);
        // ...
    } catch (Exception e) {
        log.warn("캐시 조회 실패: productId={}", productId, e);
        return null; // DB 조회로 폴백
    }
}
```

---

### 4. 모니터링

#### 로컬 캐시 (@Cacheable)
- Caffeine `recordStats()` 활성화
- Micrometer를 통한 Prometheus 노출

#### 글로벌 캐시 (RedisTemplate)
- Redis 명령어 실행 시간 로깅
- 캐시 히트/미스율 추적
- Redis 메모리 사용량 모니터링

---

## 요약

| 기준 | @Cacheable (로컬) | RedisTemplate (글로벌) |
|------|-------------------|------------------------|
| **일관성** | 인스턴스별로 다를 수 있음 | 모든 인스턴스에서 동일 |
| **성능** | 매우 빠름 (마이크로초) | 빠름 (밀리초) |
| **메모리** | 인스턴스 메모리 제한 | 대용량 가능 |
| **구현 복잡도** | 매우 간단 | 복잡 |
| **무효화** | 단순 (TTL, @CacheEvict) | 복잡 (Lua, 패턴 매칭) |
| **적용 대상** | 마스터 데이터, 사용자 정보 | 핵심 도메인, 대용량 데이터 |

**핵심 원칙:**
- **로컬 캐시**: 인스턴스 간 일관성이 중요하지 않고, 빠른 응답이 필요한 경우
- **글로벌 캐시**: 인스턴스 간 일관성이 중요하고, 복잡한 캐시 로직이 필요한 경우

---

## 참고 문서

- [캐시 패턴 적용 가이드](./cache-patterns-application-guide.md)
- [로컬 캐시 적용 고려 사항](./local-cache-considerations.md)
- [Redis Eviction Policy 분석](./redis/eviction-policy-analysis.md)

