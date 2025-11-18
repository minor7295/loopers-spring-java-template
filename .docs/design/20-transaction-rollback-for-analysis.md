# @Transactional rollbackFor 명시 필요성 분석

## 📌 개요

본 문서는 `@Transactional`에 `rollbackFor`를 명시적으로 지정하는 것이 필요한지 판단합니다.

---

## 🎯 Spring @Transactional의 기본 롤백 동작

### 기본 롤백 규칙

**Spring의 `@Transactional` 기본 동작**:
- ✅ **롤백**: `RuntimeException`과 `Error` 발생 시
- ❌ **롤백 안 함**: `checked exception` (예: `IOException`, `SQLException`) 발생 시

**이유**:
- `checked exception`은 비즈니스 로직의 일부로 처리될 수 있음
- 예: 파일 읽기 실패 시 재시도하거나 다른 파일을 읽는 경우

---

## 🔍 현재 프로젝트 분석

### 1. 예외 처리 패턴

#### 현재 사용하는 예외

**CoreException (RuntimeException 상속)**:
```java
public class CoreException extends RuntimeException {
    // 모든 비즈니스 예외는 RuntimeException
}
```

**Spring 예외들 (RuntimeException 상속)**:
- `DataIntegrityViolationException` (RuntimeException 상속)
- `OptimisticLockingFailureException` (RuntimeException 상속)

#### 현재 @Transactional 사용 패턴

```java
@Transactional  // rollbackFor 명시 없음
public OrderInfo createOrder(String userId, List<OrderItemCommand> commands) {
    // CoreException 발생 시 자동 롤백 (RuntimeException이므로)
}
```

**결론**: ✅ **현재 프로젝트는 `rollbackFor` 명시 불필요**
- 모든 예외가 `RuntimeException` 계열
- 기본 동작으로 롤백됨

---

## 📊 rollbackFor 명시 필요성 판단 기준

### ✅ rollbackFor 명시가 **불필요한** 경우

#### 1. RuntimeException만 사용하는 경우

**현재 프로젝트 상황**:
```java
@Transactional  // ✅ rollbackFor 불필요
public OrderInfo createOrder(...) {
    // CoreException (RuntimeException) 발생 시 자동 롤백
    throw new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다.");
}
```

**이유**:
- `RuntimeException`은 기본적으로 롤백됨
- 명시할 필요 없음

#### 2. checked exception을 사용하지 않는 경우

**현재 프로젝트**:
- ❌ `IOException` 사용 없음
- ❌ `SQLException` 직접 사용 없음 (JPA가 처리)
- ✅ 모든 예외가 `RuntimeException` 계열

---

### ⚠️ rollbackFor 명시가 **필요한** 경우

#### 1. checked exception을 던지고 롤백하고 싶은 경우

**예시: 파일 I/O 작업**:
```java
@Transactional(rollbackFor = IOException.class)  // ✅ 명시 필요
public void processFile(String filePath) throws IOException {
    // 파일 읽기
    String content = Files.readString(Paths.get(filePath));
    
    // DB 저장
    orderRepository.save(order);
    
    // IOException 발생 시 롤백하고 싶은 경우
}
```

**이유**:
- `IOException`은 checked exception
- 기본적으로 롤백되지 않음
- 명시적으로 지정해야 롤백됨

#### 2. 특정 RuntimeException을 롤백하지 않으려는 경우

**예시: 비즈니스 예외는 롤백하지 않음**:
```java
@Transactional(noRollbackFor = BusinessException.class)
public void processOrder(...) {
    // BusinessException 발생 시 롤백하지 않음
}
```

**이유**:
- 특정 예외는 롤백하지 않고 커밋하고 싶은 경우
- 드문 경우이지만 필요할 수 있음

---

## 🎯 판단 기준표

| 상황 | rollbackFor 명시 필요 | 이유 |
|------|---------------------|------|
| **RuntimeException만 사용** | ❌ 불필요 | 기본 동작으로 롤백됨 |
| **checked exception 사용 안 함** | ❌ 불필요 | 롤백 대상 없음 |
| **checked exception + 롤백 필요** | ✅ 필요 | 명시적으로 지정해야 함 |
| **특정 예외 롤백 제외** | ✅ 필요 | `noRollbackFor` 사용 |

---

## 💡 현재 프로젝트 권장 사항

### ✅ 현재 상태 유지 (rollbackFor 명시 불필요)

**이유**:
1. **모든 예외가 RuntimeException 계열**
   - `CoreException extends RuntimeException`
   - Spring 예외들도 `RuntimeException` 상속

2. **checked exception 사용 없음**
   - 파일 I/O 없음
   - 네트워크 I/O 없음
   - 외부 API 호출 없음

3. **명시적 지정의 단점**
   - 코드 복잡도 증가
   - 유지보수 부담 증가
   - 불필요한 명시는 오히려 혼란 야기

### ⚠️ 향후 추가 시 고려사항

**파일 I/O 추가 시**:
```java
@Transactional(rollbackFor = IOException.class)  // ✅ 명시 필요
public void processFile(String filePath) throws IOException {
    // 파일 읽기
    String content = Files.readString(Paths.get(filePath));
    
    // DB 저장
    orderRepository.save(order);
}
```

**외부 API 호출 추가 시**:
```java
// 외부 API 호출이 checked exception을 던지는 경우
@Transactional(rollbackFor = {IOException.class, ConnectException.class})
public void callExternalApi(...) throws IOException, ConnectException {
    // 외부 API 호출
    // 실패 시 롤백하고 싶은 경우
}
```

---

## 📋 비교표: rollbackFor 명시 vs 미명시

| 항목 | rollbackFor 미명시 (현재) | rollbackFor 명시 |
|------|------------------------|-----------------|
| **코드 간결성** | ✅ 간결 | ⚠️ 복잡 |
| **가독성** | ✅ 명확 (기본 동작) | ⚠️ 명시적이지만 복잡 |
| **유지보수** | ✅ 쉬움 | ⚠️ 예외 추가 시 수정 필요 |
| **RuntimeException** | ✅ 자동 롤백 | ✅ 동일 (명시 불필요) |
| **checked exception** | ❌ 롤백 안 됨 | ✅ 명시 시 롤백 |
| **현재 프로젝트** | ✅ 적절 | ❌ 불필요 |

---

## 🎯 실무 권장 사항

### 1. 기본 원칙: 명시적이지 않으면 기본 동작 사용

**권장**:
- `RuntimeException`만 사용하는 경우: `rollbackFor` 명시 안 함
- 기본 동작으로 충분한 경우: 명시 안 함

**이유**:
- 코드 간결성
- Spring의 기본 동작 이해
- 불필요한 복잡도 방지

### 2. 예외적인 경우에만 명시

**명시가 필요한 경우**:
1. **checked exception을 던지고 롤백하고 싶을 때**
   ```java
   @Transactional(rollbackFor = IOException.class)
   public void processFile(...) throws IOException { }
   ```

2. **특정 예외를 롤백하지 않으려 할 때**
   ```java
   @Transactional(noRollbackFor = BusinessException.class)
   public void processOrder(...) { }
   ```

### 3. 현재 프로젝트 권장

**✅ rollbackFor 명시하지 않음 (현재 상태 유지)**

**이유**:
- 모든 예외가 `RuntimeException` 계열
- 기본 동작으로 충분
- 코드 간결성 유지

**향후 추가 시**:
- 파일 I/O 추가 시: `rollbackFor = IOException.class` 고려
- 외부 API 호출 추가 시: 해당 예외에 대해 `rollbackFor` 고려

---

## 📊 종합 평가

### 현재 프로젝트

| 항목 | 평가 | 설명 |
|------|------|------|
| **예외 타입** | ✅ RuntimeException만 사용 | CoreException, Spring 예외 모두 RuntimeException |
| **checked exception** | ✅ 사용 없음 | IOException, SQLException 등 없음 |
| **rollbackFor 명시** | ❌ 불필요 | 기본 동작으로 충분 |
| **권장 사항** | ✅ 현재 상태 유지 | 명시하지 않음 |

### 일반적인 관행

**규모가 큰 서비스에서의 관행**:
- ✅ **대부분 rollbackFor 명시 안 함**
- ✅ **RuntimeException만 사용하는 경우 명시 불필요**
- ⚠️ **checked exception 사용 시에만 명시**

**이유**:
- 코드 간결성
- Spring의 기본 동작 이해
- 불필요한 복잡도 방지

---

## 🔗 관련 문서

- [트랜잭션 관리 관행](./17-transaction-management-practices.md)
- [동시성 처리 설계 원칙](./15-concurrency-design-principles.md)

