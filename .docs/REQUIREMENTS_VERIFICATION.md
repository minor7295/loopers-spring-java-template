# 요구사항 반영 검증 보고서

## 요구사항 목록

### ✔ PG 연동 대응
1. FeignClient/RestTemplate 기반 PG 요청 구현
2. timeout 설정
3. timeout/실패 시 fallback 처리
4. callback API 구현
5. callback 도착 시 PG 조회 API로 교차 검증
6. 주문 테이블에 결제 상태 관리(PENDING 포함)

### ✔ Resilience
1. Circuit Breaker 설정 적용
2. PG 장애 시 fallback 후 우리 서비스는 정상 응답 유지
3. callback이 안 오더라도 결제 상태를 복구할 수 있는 구조
4. timeout 실패 시에도 PG 상태 조회로 최종 상태 반영

---

## 검증 결과

### ✔ PG 연동 대응

#### 1. ✅ FeignClient/RestTemplate 기반 PG 요청 구현

**위치**: `PaymentGatewayClient.java`

**구현 내용**:
```java
@FeignClient(
    name = "paymentGatewayClient",
    url = "${payment-gateway.url}",
    path = "/api/v1/payments",
    fallback = PaymentGatewayClientFallback.class
)
public interface PaymentGatewayClient {
    @PostMapping
    PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> requestPayment(...);
    
    @GetMapping("/{transactionKey}")
    PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionDetailResponse> getTransaction(...);
    
    @GetMapping
    PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> getTransactionsByOrder(...);
}
```

**검증 결과**: ✅ **완벽하게 구현됨**
- FeignClient 기반 PG 요청 구현
- 결제 요청, 조회 API 모두 구현됨

---

#### 2. ✅ timeout 설정

**위치**: `application.yml`

**구현 내용**:
```yaml
feign:
  client:
    config:
      paymentGatewayClient:
        connectTimeout: 2000 # 연결 타임아웃 (2초)
        readTimeout: 6000 # 읽기 타임아웃 (6초) - PG 처리 지연 1s~5s 고려

resilience4j:
  timelimiter:
    instances:
      paymentGatewayClient:
        timeoutDuration: 6s # 타임아웃 시간 (Feign readTimeout과 동일)
```

**검증 결과**: ✅ **완벽하게 구현됨**
- 연결 타임아웃: 2초
- 읽기 타임아웃: 6초 (PG 처리 지연 1s~5s 고려)
- TimeLimiter: 6초

---

#### 3. ✅ timeout/실패 시 fallback 처리

**위치**: `PaymentGatewayClientFallback.java`

**구현 내용**:
```java
@Component
public class PaymentGatewayClientFallback implements PaymentGatewayClient {
    @Override
    public PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> requestPayment(...) {
        // CIRCUIT_BREAKER_OPEN 에러 코드 반환
        return new PaymentGatewayDto.ApiResponse<>(
            new PaymentGatewayDto.ApiResponse.Metadata(
                PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
                "CIRCUIT_BREAKER_OPEN",
                "PG 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
            ),
            null
        );
    }
}
```

**Fallback 응답 처리**:
```java
// PurchasingFacade.requestPaymentToGateway()
if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
    log.info("CircuitBreaker가 Open 상태입니다. Fallback이 호출되었습니다. 주문은 PENDING 상태로 유지됩니다.");
    return null; // 주문은 PENDING 상태로 유지
}
```

**검증 결과**: ✅ **완벽하게 구현됨**
- Fallback 클래스 구현: 모든 메서드에 대해 구현
- Fallback 응답 처리: CIRCUIT_BREAKER_OPEN 구분 처리
- 주문 상태 PENDING 유지: timeout/실패/circuit open 시 주문을 PENDING 상태로 유지

---

#### 4. ✅ callback API 구현

**위치**: `PurchasingV1Controller.java`, `PurchasingFacade.java`

**구현 내용**:
```java
// Controller
@PostMapping("/{orderId}/callback")
public ApiResponse<Void> handlePaymentCallback(
    @PathVariable Long orderId,
    @RequestBody PaymentGatewayDto.CallbackRequest callbackRequest
) {
    purchasingFacade.handlePaymentCallback(orderId, callbackRequest);
    return ApiResponse.success();
}

// Facade
@Transactional
public void handlePaymentCallback(Long orderId, PaymentGatewayDto.CallbackRequest callbackRequest) {
    // 결제 성공/실패에 따라 주문 상태 업데이트
    if (status == PaymentGatewayDto.TransactionStatus.SUCCESS) {
        order.complete();
    } else if (status == PaymentGatewayDto.TransactionStatus.FAILED) {
        cancelOrder(order, user);
    }
}
```

**검증 결과**: ✅ **완벽하게 구현됨**
- 콜백 엔드포인트: `POST /api/v1/orders/{orderId}/callback`
- 콜백 처리 로직: 결제 성공/실패에 따라 주문 상태 업데이트

---

#### 5. ✅ callback 도착 시 PG 조회 API로 교차 검증

**위치**: `PurchasingFacade.handlePaymentCallback()`, `PurchasingFacade.verifyCallbackWithPgInquiry()`

**구현 내용**:
```java
@Transactional
public void handlePaymentCallback(Long orderId, PaymentGatewayDto.CallbackRequest callbackRequest) {
    // 콜백 정보와 PG 원장 교차 검증
    PaymentGatewayDto.TransactionStatus verifiedStatus = verifyCallbackWithPgInquiry(
        order.getUserId(), orderId, callbackRequest);
    
    // PG 원장을 우선시하여 처리 (불일치 시 PG 원장 기준)
    if (verifiedStatus == PaymentGatewayDto.TransactionStatus.SUCCESS) {
        order.complete();
    } else if (verifiedStatus == PaymentGatewayDto.TransactionStatus.FAILED) {
        cancelOrder(order, user);
    }
}

private PaymentGatewayDto.TransactionStatus verifyCallbackWithPgInquiry(...) {
    // PG에서 주문별 결제 정보 조회 (스케줄러 전용 클라이언트 사용 - Retry 적용)
    PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
        paymentGatewaySchedulerClient.getTransactionsByOrder(userIdString, String.valueOf(orderId));
    
    // 가장 최근 트랜잭션의 상태 확인 (PG 원장 기준)
    PaymentGatewayDto.TransactionStatus pgStatus = latestTransaction.status();
    PaymentGatewayDto.TransactionStatus callbackStatus = callbackRequest.status();
    
    // 콜백 정보와 PG 조회 결과 비교
    if (pgStatus != callbackStatus) {
        // 불일치 시 PG 원장을 우선시하여 처리
        log.warn("콜백 정보와 PG 원장이 불일치합니다. PG 원장을 우선시하여 처리합니다.");
        return pgStatus; // PG 원장 기준으로 처리
    }
    
    return pgStatus;
}
```

**검증 결과**: ✅ **완벽하게 구현됨**
- 콜백 도착 시 주문 상태 업데이트: ✅ 구현됨
- PG 조회 API로 교차 검증: ✅ 구현됨 (`verifyCallbackWithPgInquiry()`)
- 불일치 시 PG 원장 우선시: ✅ 구현됨
- 콜백 정보와 PG 조회 결과 검증: ✅ 구현됨
- PG 조회 실패 시 Fallback: ✅ 콜백 정보 사용하되 경고 로그 기록

---

#### 6. ✅ 주문 테이블에 결제 상태 관리(PENDING 포함)

**위치**: `OrderStatus.java`, `Order.java`

**구현 내용**:
```java
// OrderStatus enum
public enum OrderStatus {
    PENDING,    // 결제 요청 중
    COMPLETED,  // 결제 완료
    CANCELED    // 결제 취소
}

// Order 생성 시 PENDING 상태로 초기화
public Order(...) {
    // ...
    this.status = OrderStatus.PENDING;
}
```

**검증 결과**: ✅ **완벽하게 구현됨**
- PENDING 상태 포함: 주문 생성 시 PENDING 상태로 초기화
- 상태 전이: PENDING → COMPLETED / CANCELED
- 상태 관리: 주문 테이블에서 결제 상태 관리

---

### ✔ Resilience

#### 1. ✅ Circuit Breaker 설정 적용

**위치**: `application.yml`

**구현 내용**:
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50 # 실패율 임계값 (50% 이상 실패 시 Open)
        waitDurationInOpenState: 10s
    instances:
      paymentGatewayClient:
        baseConfig: default
        failureRateThreshold: 50
```

**검증 결과**: ✅ **완벽하게 구현됨**
- Circuit Breaker 설정: 실패율 임계값 50%
- 슬라이딩 윈도우: 10
- 최소 호출 횟수: 5회
- 자동 상태 전환: OPEN → HALF_OPEN → CLOSED

---

#### 2. ✅ PG 장애 시 fallback 후 우리 서비스는 정상 응답 유지

**위치**: `PaymentGatewayClientFallback.java`, `PurchasingFacade.createOrder()`

**구현 내용**:
```java
// Fallback 응답
return new PaymentGatewayDto.ApiResponse<>(
    new PaymentGatewayDto.ApiResponse.Metadata(
        PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
        "CIRCUIT_BREAKER_OPEN",
        "PG 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
    ),
    null
);

// Fallback 응답 처리
if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
    log.info("CircuitBreaker가 Open 상태입니다. 주문은 PENDING 상태로 유지됩니다.");
    return null; // 주문은 PENDING 상태로 유지
}

// createOrder()는 정상적으로 주문을 반환 (PENDING 상태)
return OrderInfo.from(savedOrder);
```

**검증 결과**: ✅ **완벽하게 구현됨**
- Fallback 호출: Circuit Breaker OPEN 시 Fallback 호출
- 정상 응답 유지: 주문은 PENDING 상태로 생성되어 정상 응답
- 외부 시스템 장애가 내부 시스템에 영향 없음

---

#### 3. ✅ callback이 안 오더라도 결제 상태를 복구할 수 있는 구조

**위치**: `PaymentRecoveryScheduler.java`, `PurchasingFacade.recoverOrderStatusByPaymentCheck()`

**구현 내용**:
```java
// 스케줄러: 1분마다 실행
@Scheduled(fixedDelay = 60000)
public void recoverPendingOrders() {
    // PENDING 상태인 주문들 조회
    List<Order> pendingOrders = orderRepository.findAllByStatus(OrderStatus.PENDING);
    
    // 각 주문에 대해 PG 결제 상태 확인 API 호출
    for (Order order : pendingOrders) {
        purchasingFacade.recoverOrderStatusByPaymentCheck(userId, order.getId());
    }
}

// 수동 복구 API
@PostMapping("/{orderId}/recover")
public ApiResponse<Void> recoverOrderStatus(...) {
    purchasingFacade.recoverOrderStatusByPaymentCheck(userId, orderId);
    return ApiResponse.success();
}
```

**검증 결과**: ✅ **완벽하게 구현됨**
- 주기적 스케줄러: 1분마다 PENDING 주문 조회 및 상태 복구
- 수동 복구 API: 관리자나 사용자가 수동으로 상태 복구 요청 가능
- PG 조회 API 활용: `getTransactionsByOrder()`로 결제 상태 확인

---

#### 4. ✅ timeout 실패 시에도 PG 상태 조회로 최종 상태 반영

**위치**: `PurchasingFacade.checkAndRecoverPaymentStatusAfterTimeout()`

**구현 내용**:
```java
catch (FeignException.TimeoutException e) {
    // 타임아웃 예외 처리
    log.error("PG 결제 요청 타임아웃 발생. (orderId: {})", orderId, e);
    
    // 타임아웃 발생 시에도 PG에서 실제 결제 상태를 확인하여 반영
    log.info("타임아웃 발생. PG 결제 상태 확인 API를 호출하여 실제 결제 상태를 확인합니다.");
    checkAndRecoverPaymentStatusAfterTimeout(userId, orderId);
    return null;
}

private void checkAndRecoverPaymentStatusAfterTimeout(String userId, Long orderId) {
    // PG에서 주문별 결제 정보 조회
    PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
        paymentGatewaySchedulerClient.getTransactionsByOrder(userId, String.valueOf(orderId));
    
    // 결제 상태에 따라 주문 상태 업데이트
    if (status == PaymentGatewayDto.TransactionStatus.SUCCESS) {
        order.complete();
    } else if (status == PaymentGatewayDto.TransactionStatus.FAILED) {
        cancelOrder(order, user);
    }
}
```

**검증 결과**: ✅ **완벽하게 구현됨**
- 타임아웃 발생 시 즉시 상태 확인: `checkAndRecoverPaymentStatusAfterTimeout()` 호출
- PG 조회 API 활용: `getTransactionsByOrder()`로 결제 상태 확인
- 최종 상태 반영: 결제 상태에 따라 주문 상태 업데이트

---

## 검증 요약

| 항목 | 요구사항 | 구현 상태 | 평가 |
|------|---------|---------|------|
| FeignClient 기반 PG 요청 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| timeout 설정 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| timeout/실패 시 fallback 처리 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| callback API 구현 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| callback 도착 시 PG 조회 API로 교차 검증 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| 주문 테이블에 결제 상태 관리(PENDING 포함) | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| Circuit Breaker 설정 적용 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| PG 장애 시 fallback 후 우리 서비스는 정상 응답 유지 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| callback이 안 오더라도 결제 상태를 복구할 수 있는 구조 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| timeout 실패 시에도 PG 상태 조회로 최종 상태 반영 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |

---

## 결론

**요구사항 충족도**: **100%** (10개 모두 완벽 구현)

### 완벽하게 구현된 항목 (10개)
1. ✅ FeignClient 기반 PG 요청 구현
2. ✅ timeout 설정
3. ✅ timeout/실패 시 fallback 처리
4. ✅ callback API 구현
5. ✅ 주문 테이블에 결제 상태 관리(PENDING 포함)
6. ✅ Circuit Breaker 설정 적용
7. ✅ PG 장애 시 fallback 후 우리 서비스는 정상 응답 유지
8. ✅ callback이 안 오더라도 결제 상태를 복구할 수 있는 구조
9. ✅ timeout 실패 시에도 PG 상태 조회로 최종 상태 반영

### 완벽하게 구현된 항목 (10개)
1. ✅ **callback 도착 시 PG 조회 API로 교차 검증**
   - 구현: `verifyCallbackWithPgInquiry()` 메서드로 콜백 정보와 PG 원장 교차 검증
   - 불일치 시 PG 원장 우선시: ✅ 구현됨
   - 검증 로직: ✅ 구현됨

---

## 최종 평가

**모든 요구사항이 완벽하게 반영되어 있습니다.**

핵심 기능들은 모두 완벽하게 구현되었으며, 보안/정합성 강화를 위한 교차 검증 로직도 추가되어 완벽합니다.

