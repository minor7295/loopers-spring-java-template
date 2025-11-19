# DBA 설득을 위한 판단 기준

## 📌 개요

본 문서는 DBA와의 협업 시 유니크 인덱스와 비관적 락 사용에 대한 설득 근거를 정리한 문서입니다.
실무에서 실제로 통하는 논리와 DBA 관점을 고려한 설득 방법을 제시합니다.

---

## ✅ 1. 유니크 인덱스 사용 근거

### 1.1 DBA의 우려 사항

DBA가 유니크 인덱스를 꺼리는 주요 이유:

1. **쓰기 성능 저하 (Write Amplification)**
   - INSERT/UPDATE 시 인덱스 정렬, 중복 여부 검사, 잠금 관리로 인한 성능 저하
   - 특히 OLTP 시스템에서 쓰기 병목 발생 가능

2. **락 경합 (Lock Contention)**
   - 유니크 키 충돌 시 갭락/넥스트키 락 발생
   - 대량 삽입 시 성능 저하

3. **장애 시 복구 비용 증가**
   - 잘못된 배치로 인한 유니크 제약 위반 → 트랜잭션 롤백 반복
   - 대용량 테이블에서 인덱스 재생성 비용 증가

4. **DB 안정성 최우선 관점**
   - 성능 저하나 락 경합 가능성이 있는 옵션을 기본적으로 회피

### 1.2 유니크 인덱스가 필요한 이유

#### ✅ 데이터 무결성 보장

**애플리케이션 레벨로는 race condition을 완전히 방지할 수 없습니다.**

```java
// ❌ 애플리케이션 레벨만으로는 중복 방지 불가능
@Transactional
public void addLike(String userId, Long productId) {
    // 1. 중복 체크
    Optional<Like> existingLike = likeRepository.findByUserIdAndProductId(user.getId(), productId);
    if (existingLike.isPresent()) {
        return;
    }
    
    // 2. 저장 시도
    // ⚠️ 문제: 동시에 두 요청이 들어오면 둘 다 "없음"으로 판단 → 둘 다 저장 시도
    Like like = Like.of(user.getId(), productId);
    likeRepository.save(like);
}
```

**레이스 컨디션 시나리오**:
```
T1: select count(*) ... where key = ? → 없음
T2: select count(*) ... where key = ? → 없음 (동시 실행)
T1: insert ... → 성공
T2: insert ... → 중복 발생! (UNIQUE 제약조건 없으면)
```

#### ✅ 금전적 손실 방지

다음 기능들은 중복 처리 시 금전적 손실이 발생할 수 있습니다:

- **포인트 중복 차감**: 동일 주문이 2번 처리되면 포인트가 2번 차감
- **쿠폰 중복 발급**: 동일 쿠폰이 2번 발급되면 금전적 손실
- **좋아요 중복**: 비즈니스 데이터 오염
- **결제 중복**: idempotency 실패로 인한 중복 결제

#### ✅ 전 세계 대규모 서비스의 표준

대부분의 대규모 서비스는 유니크 인덱스를 필수적으로 사용합니다:

- 결제 트랜잭션 ID
- 주문 번호
- 이메일/계정
- 중복 좋아요 (user_id + product_id)
- 쿠폰 발급 히스토리 (unique(user_id, coupon_id))
- 재고 감소 테이블의 idempotency key

### 1.3 DBA 설득 전략

#### 🎯 (A) 트래픽 패턴으로 설득

**설득 문구**:
> "해당 테이블의 쓰기 QPS가 어느 정도인지 보셨나요?
> 좋아요/쿠폰/포인트는 고 QPS write-heavy 테이블이 아닙니다.
> 전체 서비스에서 차지하는 비중도 낮고, unique index가 병목을 일으킬 가능성은 거의 없습니다."

#### 🎯 (B) 금전적 손실 가능성 제시

**설득 문구**:
> "이 테이블에서 유니크 인덱스를 빼면 어떻게 장애가 발생하는지
> 책임 범위가 개발팀이 아니라 DBA 쪽으로 넘어갈 수 있습니다."

**구체적 사고 시나리오**:
- 포인트 2번 차감 → 고객 문의 증가
- 쿠폰 중복 발급 → 금전 손실
- 동일 결제요청 2번 insert → 결제 중복 billing

#### 🎯 (C) 애플리케이션 레벨 한계 명시

**설득 문구**:
> "동일 시점 동시 요청 → select 시점엔 중복 없음 → insert 2번
> 이는 애플리케이션 락/분산 락/캐시로도 100% 방지 불가
> DB만이 강한 무결성(Strong Consistency)을 제공할 수 있음"

#### 🎯 (D) 대안 제시 방식

**설득 문구**:
> "유니크 인덱스가 부담되시면 아래 방식 중 어떤 게 적절할지 검토 부탁드립니다.
> 
> - Option A: (선호) 유니크 제약 + partial index
> - Option B: UUID 기반 PK로 충돌 가능성 자체를 제거
> - Option C: INSERT ON DUPLICATE KEY UPDATE로 락 경합 최소화
> - Option D: 파티션 테이블로 부하 분산
> 
> 3번 방법 정도면 write bottleneck 거의 없습니다."

#### 🎯 (E) DBA 용어로 설명

**설득 문구**:
> "이 테이블은 키 분포가 고르게 퍼져 있어 hotspot이 없고
> cardinality도 충분히 높기 때문에, unique index가 성능 병목을 만들 가능성은 거의 없습니다."

**핵심 키워드**:
- `hotspot 없음`
- `cardinality 높음`
- `QPS 낮음`
- `index cardinality`
- `write amplification 감소`

### 1.4 최종 설득 템플릿

#### 짧은 버전 (3줄)

> "해당 테이블은 쓰기 QPS가 낮고 hotspot이 없는 구조라서 unique index에 의한 write bottleneck 가능성이 거의 없습니다.
> 또한 애플리케이션 레벨에서는 경쟁 상태를 100% 방지할 수 없기 때문에 데이터 무결성 보장을 위해 DB 제약이 필요합니다.
> 성능 영향 우려가 있으시다면 ON DUPLICATE KEY UPDATE 사용 등 DBA 권한 내에서 최적 접근법을 함께 검토하겠습니다."

#### 긴 버전 (상세)

> "좋아요/쿠폰/포인트/재고 기능은 경쟁 상태가 발생하기 쉽고,
> 애플리케이션 레벨에서는 동시성 이슈(레이스 컨디션)를 완전히 제거할 수 없습니다.
> 
> 유니크 인덱스는 이 영역에서 데이터 무결성을 보장하는 가장 안전하고 비용 효율적인 옵션입니다.
> 현재 트래픽과 키 분포상, unique index가 hotspot이나 lock contention을 유발할 가능성은 매우 낮습니다.
> 
> 만약 성능 영향이 우려되신다면
> - INSERT ON DUPLICATE KEY UPDATE
> - non-blocking index build
> - 파티셔닝
> 같은 대안을 포함해 최적 구성을 함께 논의드리고 싶습니다."

---

## ✅ 2. 비관적 락 사용 근거

### 2.1 DBA의 우려 사항

DBA가 비관적 락을 적극적으로 반대하는 이유:

1. **긴 락 보유 시간으로 인한 DB 정지 가능성**
   - 트랜잭션이 길어지거나 애플리케이션 장애 시 락이 해제되지 않음
   - 네트워크 끊김, GC pause, 배포 중지 시 락 유지

2. **락 경합 (Lock Contention)**
   - 동시 요청이 몰리면 병목 발생
   - 락 대기, deadlock, timeout, 슬로우 쿼리 증가

3. **락 범위가 예상보다 넓음**
   - MySQL(InnoDB)은 gap lock, next-key lock, range lock 등이 걸림
   - 개발자는 "한 row만 잠그는 줄" 알지만 실제론 더 크게 잠김

4. **커밋/롤백 관리 실패 시 DB 지옥 모드**
   - 커밋을 안 해서 락이 풀리지 않음
   - 커넥션 풀에서 세션이 반환되지 않음
   - 무한 재시도 로직으로 락 대기 증가

5. **모니터링 어려움**
   - 락 잡고 있는 세션을 실시간 탐지하기 어려움

6. **확장성 문제**
   - 샤딩 어려움, 리플리카 사용 어려움
   - 분산 환경에서 락이 더 위험

7. **장애 시 DBA 책임 증가**
   - 비관적 락이 원인인 장애는 대부분 DBA가 긴급 대응해야 함

### 2.2 비관적 락이 필요한 이유

#### ✅ 애플리케이션 레벨로는 race condition을 완전히 해결할 수 없음

**재고/포인트/쿠폰/좋아요 같은 기능은 동시에 업데이트 되는 shared mutable state입니다.**

애플리케이션 레벨에서는 동시성 충돌을 확률적으로 줄일 수는 있지만,
완전히 제거할 수는 없습니다.

DB만이 단일한 강한 일관성(Strong Consistency)을 제공합니다.

#### ✅ Lost Update 방지

**문제 시나리오**:
```
T1: 재고 조회 (stock = 10)
T2: 재고 조회 (stock = 10)  ← 동시에 같은 값 읽음
T1: 재고 차감 (10 - 3 = 7) → 저장
T2: 재고 차감 (10 - 5 = 5) → 저장  ← T1의 변경사항 손실!
```

**비관적 락 사용 시**:
```
T1: SELECT ... FOR UPDATE (락 획득) → stock = 10
T2: 대기... (T1이 락을 해제할 때까지)
T1: 재고 차감 (10 - 3 = 7) → 저장 → 커밋 (락 해제)
T2: 락 획득 → stock = 7 → 재고 차감 (7 - 5 = 2) → 저장
```

### 2.3 DBA 설득 전략

#### 🎯 (A) 낙관적 락을 기본으로 하고 비관적 락은 제한적으로 사용

**설득 문구**:
> "비관적 락이 우려되신다면, 기본 전략을
> Optimistic Lock(Versioning) + Retry
> 로 하겠습니다.
> 
> 단, 다음 범위에는 불가피하게 비관적 락이 필요합니다."

**현재 프로젝트 적용**:
- ✅ **낙관적 락**: 쿠폰 사용 (`UserCoupon`의 `@Version` 필드)
- ✅ **비관적 락**: 포인트 차감, 재고 차감 (금전적 손실 방지)

#### 🎯 (B) 트랜잭션이 짧고 외부 I/O가 없음을 명시

**설득 문구**:
> "비관적 락은 전역적으로 사용하지 않고,
> 금전적 손실 가능성이 있는 특정 도메인에서 only-row-level로 짧은 시간만 사용합니다.
> 
> 트랜잭션 내부에 외부 I/O는 없으며, lock holding time이 짧고 contention이 거의 없기 때문에
> 장애 위험이 매우 낮습니다."

**현재 프로젝트 확인 사항**:
- ✅ 트랜잭션 내부에 외부 API 호출 없음
- ✅ 트랜잭션이 매우 짧음 (몇 ms)
- ✅ 특정 row 1개만 `SELECT ... FOR UPDATE`
- ✅ PK/UNIQUE 인덱스 기반 조회로 Lock 범위 최소화

#### 🎯 (C) 금전적 손실 가능성 제시

**설득 문구**:
> "비관적 락이 없으면:
> - 재고 oversell 발생
> - 포인트 중복 차감
> - 쿠폰 중복 발급
> - 결제 중복 승인 idempotency 실패
> 
> 특히 '금전적 손실'을 말하면 DBA는 태도가 달라집니다."

#### 🎯 (D) DBA 용어로 설명

**설득 문구**:
> "해당 트랜잭션은 좁은 범위의 scoped locking이며,
> lock holding time이 짧고, contention 발생 가능성이 낮습니다.
> 
> deadlock risk를 고려해 lock ordering도 명확하게 설계했습니다."

**핵심 키워드**:
- `strong consistency`
- `mutual exclusion`
- `hotspot 없음`
- `low contention`
- `short-lived locking`
- `scoped locking`
- `deadlock risk minimized`
- `no external I/O in transaction`
- `predictable QPS`

### 2.4 최종 설득 템플릿

#### 짧은 버전

> "비관적 락은 전역적으로 사용하지 않고,
> 금전적 손실 가능성이 있는 특정 도메인에서 only-row-level로 짧은 시간만 사용합니다.
> 
> 트랜잭션 내부에 외부 I/O는 없으며, lock holding time이 짧고 contention이 거의 없기 때문에
> 장애 위험이 매우 낮습니다.
> 
> 애플리케이션 레벨의 논리만으로는 race condition을 100% 방지할 수 없어서,
> strong consistency 확보를 위해 필요한 최소 범위에서만 비관적 락이 필요합니다."

#### 긴 버전 (가장 효과적)

> "비관적 락은 기본 전략이 아니며
> 전체 시스템에서 쓰는 것이 아니라
> 포인트/재고/쿠폰/결제 같은 금전적 손실 위험이 있는 도메인에만
> row 단위로 짧게 적용할 계획입니다.
> 
> 트랜잭션 내부에는 외부 API 호출이나 I/O가 없어 lock holding time은 매우 짧습니다.
> 또한 lock ordering을 명확히 해서 deadlock 가능성도 줄였습니다.
> 
> 애플리케이션 레벨만으로는 race condition을 완전히 제거할 수 없어서
> strong consistency를 보장하려면 DB 차원의 mutual exclusion이 필요합니다.
> 
> 필요 범위를 명확히 제한하고,
> 기본 정책은 Optimistic Locking + Retry로 유지한 상태에서
> 꼭 필요한 영역만 비관적 락을 사용하겠습니다."

---

## ✅ 3. 프로젝트 적용 현황

### 3.1 유니크 인덱스 사용 현황

| 엔티티 | 유니크 제약조건 | 목적 | DBA 설득 근거 |
|--------|----------------|------|--------------|
| `Like` | `(ref_user_id, ref_product_id)` | 중복 좋아요 방지 | 애플리케이션 레벨로는 race condition 완전 방지 불가 |
| `UserCoupon` | `(ref_user_id, ref_coupon_id)` | 중복 쿠폰 발급 방지 | 금전적 손실 방지, 쿠폰 중복 발급 방지 |

### 3.2 비관적 락 사용 현황

| 기능 | 락 타입 | 사용 위치 | DBA 설득 근거 |
|------|---------|----------|--------------|
| 포인트 차감 | `PESSIMISTIC_WRITE` | `PurchasingFacade.createOrder()` | 금전적 손실 방지, Lost Update 방지 |
| 재고 차감 | `PESSIMISTIC_WRITE` | `PurchasingFacade.createOrder()` | 재고 oversell 방지, Lost Update 방지 |
| 쿠폰 사용 | `OPTIMISTIC_LOCK` | `PurchasingFacade.applyCoupon()` | Hot Spot 대응, 낙관적 락으로 충분 |

### 3.3 비관적 락 사용 시 보장 사항

✅ **트랜잭션이 짧음**: 몇 ms 내에 완료
✅ **외부 I/O 없음**: 트랜잭션 내부에 외부 API 호출 없음
✅ **Lock 범위 최소화**: PK/UNIQUE 인덱스 기반 조회로 해당 행만 락
✅ **특정 도메인만 사용**: 포인트/재고 차감에만 제한적으로 사용
✅ **낙관적 락 기본 전략**: 쿠폰 사용은 낙관적 락 사용

---

## 🔚 결론

### 유니크 인덱스

- ✅ **필수**: 데이터 무결성 보장을 위해 반드시 필요
- ✅ **애플리케이션 레벨 한계**: race condition을 완전히 방지할 수 없음
- ✅ **금전적 손실 방지**: 포인트/쿠폰 중복 방지
- ✅ **DBA 설득 포인트**: 트래픽 패턴, 금전적 손실 가능성, 애플리케이션 레벨 한계

### 비관적 락

- ✅ **제한적 사용**: 전역이 아닌 특정 도메인에만 사용
- ✅ **낙관적 락 기본**: 기본 전략은 낙관적 락 + Retry
- ✅ **트랜잭션 최소화**: 짧고, 외부 I/O 없고, Lock 범위 최소화
- ✅ **DBA 설득 포인트**: 금전적 손실 방지, strong consistency 필요, 제한적 사용

### DBA 설득의 핵심

1. **트래픽 패턴 제시**: QPS가 낮고 hotspot이 없음을 명시
2. **금전적 손실 가능성**: 구체적 사고 시나리오 제시
3. **애플리케이션 레벨 한계**: race condition을 완전히 방지할 수 없음을 명시
4. **대안 제시**: DBA가 선택할 수 있는 옵션 제공
5. **DBA 용어 사용**: hotspot, cardinality, contention 등 전문 용어 사용

