# Redis Lua 스크립트 최적화 가능성 평가

## 현재 구현 분석

### Redis 사용 패턴
1. **단순 조회/저장**: `GET`, `SET` (TTL 포함)
2. **캐시 무효화**: `KEYS` + `DELETE` (2번의 네트워크 왕복)
3. **배치 삭제**: `KEYS`로 패턴 매칭 후 `DELETE`

### 문제점

#### 1. `KEYS` 명령어 사용 (심각)
```java
// 현재 구현
Set<String> keys = redisTemplate.keys(CACHE_KEY_PATTERN_LIST);
redisTemplate.delete(keys);
```

**문제점**:
- `KEYS`는 O(N) 연산 (N = 전체 키 수)
- Redis를 블로킹하여 다른 요청 처리 불가
- 프로덕션 환경에서 위험
- 키가 많을수록 성능 저하

**영향**:
- 캐시 무효화 시 Redis 서버 블로킹
- 다른 요청 지연
- 높은 트래픽 시 서비스 중단 가능

#### 2. 여러 번의 네트워크 왕복
- `KEYS` 호출 → 결과 수신 → `DELETE` 호출
- 2번의 네트워크 왕복으로 인한 지연

#### 3. 원자성 부족
- `KEYS`와 `DELETE` 사이에 다른 요청이 개입 가능
- 일관성 문제 가능

## Lua 스크립트 최적화 가능성

### ✅ 높은 우선순위: 캐시 무효화 최적화

#### 현재 구현
```java
// 비효율적: KEYS 사용 + 2번의 네트워크 왕복
Set<String> keys = redisTemplate.keys(CACHE_KEY_PREFIX_LIST + "brand:" + brandId + ":*");
if (keys != null && !keys.isEmpty()) {
    redisTemplate.delete(keys);
}
```

#### Lua 스크립트 최적화
```lua
-- SCAN 기반 패턴 매칭 + 삭제 (원자적, 비블로킹)
local cursor = "0"
local deleted = 0
repeat
    local result = redis.call("SCAN", cursor, "MATCH", ARGV[1], "COUNT", 100)
    cursor = result[1]
    local keys = result[2]
    for i = 1, #keys do
        redis.call("DEL", keys[i])
        deleted = deleted + 1
    end
until cursor == "0"
return deleted
```

**장점**:
- ✅ 비블로킹: `SCAN` 사용으로 Redis 블로킹 방지
- ✅ 원자성: 하나의 스크립트로 실행
- ✅ 네트워크 왕복 최소화: 1번의 호출
- ✅ 안전성: 프로덕션 환경에서 안전

**성능 개선**:
- 네트워크 왕복: 2회 → 1회
- Redis 블로킹: O(N) 블로킹 → 비블로킹
- 처리 시간: 키 수에 비례 → 일정한 청크 단위 처리

### ⚠️ 중간 우선순위: Check-and-Set 패턴

#### 현재 구현
```java
// 단순 저장 (조건 없음)
redisTemplate.opsForValue().set(key, value, CACHE_TTL);
```

#### Lua 스크립트 최적화 가능성
```lua
-- 조건부 업데이트 (예: TTL이 남아있을 때만 업데이트)
local current = redis.call("GET", KEYS[1])
if current == false or tonumber(redis.call("TTL", KEYS[1])) < ARGV[2] then
    redis.call("SET", KEYS[1], ARGV[1], "EX", ARGV[2])
    return 1
else
    return 0
end
```

**적용 가능성**: ⚠️ 낮음
- 현재는 단순 캐시 저장이므로 조건부 업데이트 불필요
- TTL이 자동으로 관리되므로 추가 로직 불필요

### ⚠️ 낮은 우선순위: 배치 조회 최적화

#### 현재 구현
```java
// 단일 키 조회
String cachedValue = redisTemplate.opsForValue().get(key);
```

#### Lua 스크립트 최적화 가능성
```lua
-- 여러 키를 한 번에 조회 (MGET과 동일)
local results = {}
for i = 1, #KEYS do
    results[i] = redis.call("GET", KEYS[i])
end
return results
```

**적용 가능성**: ❌ 불필요
- 현재는 단일 키 조회만 사용
- `MGET` 명령어로 충분 (Lua 스크립트 불필요)

## 권장 최적화 사항

### 1. 캐시 무효화 최적화 (필수) ⭐

**우선순위**: 높음
**영향**: 높음 (프로덕션 안정성)
**구현 난이도**: 중간

**구현 방법**:
1. Lua 스크립트 작성 (SCAN 기반)
2. Spring의 `DefaultRedisScript` 사용
3. `evictProductListCacheByBrand()`, `evictAllProductListCache()` 메서드 수정

**예상 효과**:
- Redis 블로킹 방지
- 네트워크 왕복 50% 감소
- 프로덕션 안정성 향상

### 2. 추가 최적화 (선택)

#### A. 캐시 히트율 통계
```lua
-- 캐시 조회 시 통계 수집 (원자적)
local value = redis.call("GET", KEYS[1])
if value then
    redis.call("INCR", "cache:hits:" .. KEYS[1])
else
    redis.call("INCR", "cache:misses:" .. KEYS[1])
end
return value
```

**적용 가능성**: ⚠️ 선택적
- 모니터링이 필요한 경우에만

#### B. 조건부 캐시 업데이트
```lua
-- 오래된 캐시만 업데이트
local ttl = redis.call("TTL", KEYS[1])
if ttl < 60 then  -- 1분 미만 남았을 때만 업데이트
    redis.call("SET", KEYS[1], ARGV[1], "EX", ARGV[2])
    return 1
end
return 0
```

**적용 가능성**: ⚠️ 선택적
- 현재 TTL 관리로 충분

## 성능 비교

### 현재 구현 (KEYS 사용)
```
요청 → KEYS 호출 (블로킹) → 결과 수신 → DELETE 호출 → 완료
시간: O(N) + 네트워크 왕복 2회
```

### Lua 스크립트 최적화
```
요청 → Lua 스크립트 실행 (SCAN 기반, 비블로킹) → 완료
시간: O(N)이지만 비블로킹 + 네트워크 왕복 1회
```

**개선 효과**:
- 네트워크 왕복: 50% 감소
- Redis 블로킹: 완전 제거
- 처리 시간: 동일하지만 다른 요청 영향 없음

## 구현 우선순위

1. **높음**: 캐시 무효화 최적화 (KEYS → SCAN 기반 Lua)
2. **중간**: 캐시 히트율 통계 (모니터링 필요 시)
3. **낮음**: 조건부 캐시 업데이트 (현재 불필요)

## 결론

### 최적화 필요성: ✅ 높음

**주요 이유**:
1. `KEYS` 명령어 사용은 프로덕션에서 위험
2. 네트워크 왕복 최소화로 성능 향상
3. 원자성 보장으로 일관성 향상

### 권장 사항
1. **즉시 적용**: 캐시 무효화를 SCAN 기반 Lua 스크립트로 변경
2. **모니터링**: 캐시 히트율 통계 추가 (선택)
3. **추가 최적화**: 필요 시 조건부 업데이트 고려

