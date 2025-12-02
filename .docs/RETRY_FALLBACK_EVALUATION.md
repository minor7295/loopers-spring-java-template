# Retry와 Fallback 구현 평가 보고서

## 1. 현재 구현 상태

### 1.1 Retry 설정
**위치**: `apps/commerce-api/src/main/resources/application.yml`

```yaml
resilience4j:
  retry:
    configs:
      default:
        maxAttempts: 3 # 최대 재시도 횟수 (초기 시도 포함)
        waitDuration: 500ms # 재시도 대기 시간
        retryExceptions:
          - feign.FeignException
          - java.net.SocketTimeoutException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - feign.FeignException$NotFound # 404는 재시도하지 않음
    instances:
      paymentGatewayClient:
        baseConfig: default
        maxAttempts: 3
        waitDuration: 500ms
```

**평가**: ✅ **적절함**
- 재시도 횟수(3회)가 적절함
- 재시도 대기 시간(500ms)이 합리적임
- 재시도할 예외와 무시할 예외가 명확히 구분됨
- 404 에러는 재시도하지 않는 것이 올바름 (클라이언트 오류)

### 1.2 Fallback 구현
**위치**: `apps/commerce-api/src/main/java/com/loopers/infrastructure/paymentgateway/PaymentGatewayClientFallback.java`

```java
@FeignClient(
    name = "paymentGatewayClient",
    url = "${payment-gateway.url}",
    path = "/api/v1/payments",
    fallback = PaymentGatewayClientFallback.class
)
```

**평가**: ⚠️ **부분적으로 적절함**

**장점**:
- 모든 메서드에 대해 Fallback 구현이 존재함
- 적절한 로깅 포함
- 명확한 에러 메시지 반환

**문제점**:
1. **Fallback이 CircuitBreaker Open 상태에서만 호출됨**
   - Retry가 모두 실패한 후에도 Fallback이 호출되지 않을 수 있음
   - FeignClient의 Fallback은 CircuitBreaker가 Open 상태일 때만 호출됨
   - Retry가 실패한 후 예외가 발생하면, CircuitBreaker가 Open 상태가 아니면 Fallback이 호출되지 않음

2. **Fallback 응답 처리 로직 부재**
   - `PurchasingFacade`에서 Fallback 응답을 받았을 때의 처리가 명확하지 않음
   - Fallback 응답의 `errorCode`가 `CIRCUIT_BREAKER_OPEN`인 경우를 구분하여 처리해야 함

### 1.3 실행 순서 및 상호작용

**예상 실행 순서**:
1. FeignClient 호출
2. Retry (최대 3회 시도)
3. Retry 실패 시 CircuitBreaker 상태 확인
4. CircuitBreaker가 Open이면 Fallback 호출
5. CircuitBreaker가 Closed이면 예외 발생

**문제점**:
- Retry가 모두 실패한 후에도 CircuitBreaker가 Closed 상태이면 Fallback이 호출되지 않음
- 이 경우 `PurchasingFacade`에서 예외를 처리해야 함

## 2. 구체적인 문제점 및 개선 사항

### 2.1 문제점 1: Retry와 Fallback의 실행 순서 불명확

**현재 동작**:
- Resilience4j의 Retry와 CircuitBreaker는 함께 사용될 수 있음
- 하지만 FeignClient의 Fallback은 CircuitBreaker가 Open 상태일 때만 호출됨
- Retry가 실패한 후 CircuitBreaker가 아직 Closed 상태이면 Fallback이 호출되지 않음

**개선 방안**:
1. **CircuitBreaker 설정 조정**: 실패율 임계값을 낮춰서 더 빠르게 Open 상태로 전환
2. **Fallback 로직 보완**: `PurchasingFacade`에서 Fallback 응답을 명시적으로 처리
3. **예외 처리 강화**: Retry 실패 후 예외 발생 시에도 주문이 PENDING 상태로 유지되도록 보장

### 2.2 문제점 2: Fallback 응답 처리 로직 부재

**현재 코드** (`PurchasingFacade.java`):
```java
PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
    paymentGatewayClient.requestPayment(userId, request);

if (response != null && response.meta() != null
    && response.meta().result() == PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS
    && response.data() != null) {
    // 성공 처리
} else {
    // 실패 처리
    String errorCode = response != null && response.meta() != null
        ? response.meta().errorCode() : "UNKNOWN";
    // ...
    if (isBusinessFailure(errorCode)) {
        handlePaymentFailure(userId, orderId, errorCode, message);
    }
}
```

**문제점**:
- Fallback 응답의 `errorCode`가 `CIRCUIT_BREAKER_OPEN`인 경우를 구분하지 않음
- `CIRCUIT_BREAKER_OPEN`은 비즈니스 실패가 아니므로 주문을 PENDING 상태로 유지해야 함

**개선 방안**:
```java
String errorCode = response != null && response.meta() != null
    ? response.meta().errorCode() : "UNKNOWN";

// CircuitBreaker Open 상태는 외부 시스템 장애로 간주
if ("CIRCUIT_BREAKER_OPEN".equals(errorCode)) {
    log.info("CircuitBreaker가 Open 상태입니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
    return null; // 주문은 PENDING 상태로 유지
}

if (isBusinessFailure(errorCode)) {
    handlePaymentFailure(userId, orderId, errorCode, message);
}
```

### 2.3 문제점 3: Retry 설정의 세밀한 제어 부족

**현재 설정**:
- 모든 `FeignException`에 대해 재시도함
- 하지만 `FeignException.BadRequest` (400)는 재시도하지 않는 것이 좋음

**개선 방안**:
```yaml
retryExceptions:
  - feign.FeignException$InternalServerError  # 500 에러만 재시도
  - feign.FeignException$ServiceUnavailable   # 503 에러만 재시도
  - java.net.SocketTimeoutException
  - java.util.concurrent.TimeoutException
ignoreExceptions:
  - feign.FeignException$BadRequest      # 400 에러는 재시도하지 않음
  - feign.FeignException$NotFound        # 404 에러는 재시도하지 않음
  - feign.FeignException$Unauthorized    # 401 에러는 재시도하지 않음
```

### 2.4 문제점 4: Exponential Backoff 미적용 ✅ **완료**

**이전 설정**:
- 고정된 대기 시간(500ms) 사용
- 재시도 횟수가 많아질수록 부하가 증가할 수 있음

**개선 완료**:
- ✅ Exponential Backoff 적용 완료
- ✅ `Resilience4jRetryConfig.java`를 통해 커스텀 `RetryConfig` 구현
- ✅ Exponential Backoff 설정:
  - 초기 대기 시간: 500ms
  - 배수(multiplier): 2 (각 재시도마다 2배씩 증가)
  - 최대 대기 시간: 5초
  - 랜덤 jitter: 활성화 (thundering herd 문제 방지)
- ✅ 재시도 시퀀스: 즉시 → 500ms → 1000ms (최대 5초)

## 3. 테스트 커버리지 평가

### 3.1 Retry 테스트
**파일**: `PurchasingFacadeRetryTest.java`

**커버리지**: ✅ **양호**
- 일시적 오류 발생 시 재시도 테스트 ✅
- 재시도 횟수 초과 시 최종 실패 처리 테스트 ✅
- 타임아웃 발생 시 재시도 테스트 ✅
- 재시도 간격(backoff) 테스트 ✅
- 4xx 에러는 재시도하지 않는 테스트 ✅

### 3.2 Fallback 테스트
**파일**: `PurchasingFacadeCircuitBreakerTest.java`

**커버리지**: ⚠️ **부분적**
- CircuitBreaker Open 상태에서 Fallback 동작 테스트 ✅
- 하지만 Retry 실패 후 Fallback 호출 테스트 부재 ⚠️
- Fallback 응답 처리 로직 테스트 부재 ⚠️

## 4. 종합 평가 및 권장 사항

### 4.1 종합 평가

| 항목 | 평가 | 점수 |
|------|------|------|
| Retry 설정 | 적절함 | 8/10 |
| Fallback 구현 | 부분적으로 적절함 | 6/10 |
| 실행 순서 및 상호작용 | 불명확함 | 5/10 |
| 예외 처리 | 양호함 | 7/10 |
| 테스트 커버리지 | 양호함 | 7/10 |
| **종합** | **부분적으로 적절함** | **6.6/10** |

### 4.2 즉시 개선 필요 사항

1. ✅ **Fallback 응답 처리 로직 추가** (완료)
   - `CIRCUIT_BREAKER_OPEN` 에러 코드를 구분하여 처리
   - 외부 시스템 장애로 간주하여 주문을 PENDING 상태로 유지
   - `PurchasingFacade.java`에 CIRCUIT_BREAKER_OPEN 에러 코드 처리 로직 추가

2. ✅ **Retry 예외 설정 세밀화** (완료)
   - 4xx 에러는 재시도하지 않도록 설정
   - 5xx 에러만 재시도하도록 제한
   - `application.yml`에 세밀한 예외 설정 추가

3. ✅ **테스트 보완** (완료)
   - Retry 실패 후 Fallback 호출 시나리오 테스트 추가
   - Fallback 응답 처리 로직 테스트 추가
   - `PurchasingFacadeCircuitBreakerTest.java`에 다음 테스트 추가:
     - `createOrder_fallbackResponseWithCircuitBreakerOpen_orderRemainsPending`: Fallback 응답의 CIRCUIT_BREAKER_OPEN 에러 코드 처리 테스트
     - `createOrder_retryFailure_circuitBreakerOpens_fallbackExecuted`: Retry 실패 후 CircuitBreaker가 OPEN 상태가 되어 Fallback이 호출되는지 테스트

4. ✅ **CircuitBreaker 설정 조정** (완료)
   - 실패율 임계값(50%)에 대한 설명 주석 추가
   - 설정값의 적절성에 대한 문서화

### 4.3 장기 개선 사항

1. ✅ **Exponential Backoff 적용** (완료)
   - 재시도 간격을 점진적으로 증가시켜 부하 감소
   - `Resilience4jRetryConfig.java`를 통해 구현 완료
   - 초기 500ms → 1000ms → 최대 5초 (랜덤 jitter 포함)

2. **CircuitBreaker 설정 조정**
   - 실패율 임계값 조정으로 더 빠른 Fallback 활성화

3. **모니터링 및 알림**
   - Retry 및 Fallback 호출 빈도 모니터링
   - CircuitBreaker 상태 변경 알림

## 5. 결론

현재 Retry와 Fallback 구현은 **기본적인 요구사항을 충족**하며, **개선 사항이 모두 적용**되었습니다:

1. ✅ Retry 설정은 적절함
2. ✅ Fallback은 CircuitBreaker Open 상태에서 호출되며, 응답 처리 로직이 구현됨
3. ✅ Fallback 응답 처리 로직이 구현되어 CIRCUIT_BREAKER_OPEN 에러 코드를 올바르게 처리함
4. ✅ 테스트 커버리지가 보완되어 주요 시나리오를 모두 커버함

**개선 완료 사항**:
- ✅ Fallback 응답 처리 로직 추가 (`PurchasingFacade.java`)
- ✅ Retry 예외 설정 세밀화 (`application.yml`)
- ✅ 테스트 보완 (`PurchasingFacadeCircuitBreakerTest.java`)
- ✅ CircuitBreaker 설정 문서화 (`application.yml`)

**최종 평가**: 모든 즉시 개선 필요 사항이 완료되어 **안정성과 신뢰성이 크게 향상**되었습니다.

