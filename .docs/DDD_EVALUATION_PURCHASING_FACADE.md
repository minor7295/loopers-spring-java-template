# PurchasingFacade DDD 관점 책임 분리 평가

## 평가 대상
- **파일**: `apps/commerce-api/src/main/java/com/loopers/application/purchasing/PurchasingFacade.java`
- **라인**: 477-859 (`requestPaymentToGateway`, `checkAndRecoverPaymentStatusAfterTimeout`, `updateOrderStatusByPaymentStatus`, `isBusinessFailure`, `handlePaymentCallback`, `verifyCallbackWithPgInquiry`)

## 평가 결과 요약

| 항목 | 평가 | 점수 |
|------|------|------|
| 단일 책임 원칙 (SRP) | ⚠️ 위반 | 3/10 |
| 도메인/인프라 분리 | ❌ 혼재 | 2/10 |
| 애플리케이션/도메인 서비스 분리 | ❌ 혼재 | 2/10 |
| 절차지향적 코드 | ❌ 심각 | 1/10 |
| 테스트 가능성 | ⚠️ 어려움 | 3/10 |
| **종합 평가** | **❌ 개선 필요** | **2.2/10** |

---

## 주요 문제점

### 1. ❌ 단일 책임 원칙 (SRP) 위반

#### 문제: `requestPaymentToGateway` 메서드 (477-604 라인)
**현재 책임:**
- ✅ 카드 타입 변환 (도메인 변환 로직)
- ✅ 콜백 URL 생성 (인프라 설정)
- ✅ PG API 호출 (인프라)
- ✅ 응답 파싱 및 검증 (도메인 로직)
- ✅ 예외 처리 (FeignException, SocketTimeoutException 등)
- ✅ 비즈니스 실패 판단 (`isBusinessFailure` 호출)
- ✅ 타임아웃 복구 로직 호출 (`checkAndRecoverPaymentStatusAfterTimeout`)
- ✅ 주문 취소 처리 (`handlePaymentFailure` 호출)

**문제점:**
- 하나의 메서드가 **8가지 이상의 책임**을 가짐
- 메서드 길이: **127 라인** (권장: 20-30 라인)
- 중첩된 try-catch 블록으로 가독성 저하

#### 문제: `checkAndRecoverPaymentStatusAfterTimeout` 메서드 (624-663 라인)
**현재 책임:**
- ❌ `Thread.sleep(1000)` - 인프라 관심사 (스레드 관리)
- ✅ PG API 호출 (인프라)
- ✅ 응답 파싱 (도메인 로직)
- ✅ 주문 상태 업데이트 (`updateOrderStatusByPaymentStatus` 호출)

**문제점:**
- 인프라 관심사(`Thread.sleep`)와 도메인 로직이 혼재
- 테스트 시 `Thread.sleep`으로 인한 느린 테스트

---

### 2. ❌ 도메인 로직과 인프라 로직 혼재

#### 예시 1: `requestPaymentToGateway` 메서드
```java
// 인프라: FeignException 처리
catch (FeignException e) {
    Request request = e.request();
    int status = e.status();
    String method = request != null ? request.httpMethod().name() : "UNKNOWN";
    String url = request != null ? request.url() : "UNKNOWN";
    
    // 도메인: 타임아웃 판단 로직
    Throwable cause = e.getCause();
    boolean isTimeout = cause instanceof SocketTimeoutException || 
                       cause instanceof TimeoutException ||
                       (e.getMessage() != null && e.getMessage().contains("timeout"));
    
    // 도메인: 비즈니스 규칙 (주문 상태 결정)
    if (isTimeout) {
        checkAndRecoverPaymentStatusAfterTimeout(userId, orderId);
    }
}
```

**문제점:**
- 인프라 예외 처리와 도메인 비즈니스 로직이 같은 메서드에 존재
- 인프라 변경 시 도메인 로직도 함께 수정해야 함

#### 예시 2: `checkAndRecoverPaymentStatusAfterTimeout` 메서드
```java
private void checkAndRecoverPaymentStatusAfterTimeout(String userId, Long orderId) {
    try {
        // ❌ 인프라 관심사: 스레드 관리
        Thread.sleep(1000);
        
        // ✅ 인프라: PG API 호출
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
            paymentGatewaySchedulerClient.getTransactionsByOrder(userId, String.valueOf(orderId));
        
        // ✅ 도메인: 응답 파싱 및 상태 업데이트
        PaymentGatewayDto.TransactionResponse latestTransaction = 
            response.data().transactions().get(response.data().transactions().size() - 1);
        updateOrderStatusByPaymentStatus(orderId, status, ...);
    }
}
```

**문제점:**
- `Thread.sleep`은 인프라 관심사인데 도메인 로직과 함께 있음
- 테스트 시 실제 대기 시간이 필요하여 느린 테스트

---

### 3. ❌ 애플리케이션 서비스와 도메인 서비스 역할 혼재

#### 문제: 비즈니스 규칙이 애플리케이션 서비스에 존재

**현재 위치**: `PurchasingFacade.isBusinessFailure()` (757-775 라인)
```java
private boolean isBusinessFailure(String errorCode) {
    // 비즈니스 규칙: 어떤 에러 코드가 비즈니스 실패인지 판단
    return errorCode.contains("LIMIT_EXCEEDED") ||
        errorCode.contains("INVALID_CARD") ||
        errorCode.contains("CARD_ERROR") ||
        errorCode.contains("INSUFFICIENT_FUNDS") ||
        errorCode.contains("PAYMENT_FAILED");
}
```

**문제점:**
- **비즈니스 규칙**이 애플리케이션 서비스에 존재
- 도메인 지식이 애플리케이션 계층에 하드코딩됨
- 다른 애플리케이션 서비스에서 재사용 불가

**올바른 위치**: 도메인 서비스 (`PaymentFailureClassifier`)

---

### 4. ❌ 절차지향적 코드 구조

#### 예시: `requestPaymentToGateway` 메서드
```java
private String requestPaymentToGateway(...) {
    try {
        // Step 1: 카드 타입 변환
        PaymentGatewayDto.CardType gatewayCardType;
        try {
            gatewayCardType = PaymentGatewayDto.CardType.valueOf(cardType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        
        // Step 2: 콜백 URL 생성
        String callbackUrl = String.format("http://localhost:8080/api/v1/orders/%d/callback", orderId);
        
        // Step 3: 요청 생성
        PaymentGatewayDto.PaymentRequest request = new PaymentGatewayDto.PaymentRequest(...);
        
        // Step 4: API 호출
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
            paymentGatewayClient.requestPayment(userId, request);
        
        // Step 5: 응답 처리
        if (response != null && response.meta() != null && ...) {
            // Step 6: 성공 처리
            return transactionKey;
        } else {
            // Step 7: 실패 처리
            if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
                return null;
            }
            if (isBusinessFailure(errorCode)) {
                handlePaymentFailure(...);
            }
            return null;
        }
    } catch (FeignException e) {
        // Step 8: 예외 처리
        if (isTimeout) {
            checkAndRecoverPaymentStatusAfterTimeout(...);
        }
        // ... 더 많은 분기
    }
}
```

**문제점:**
- 순차적인 절차로만 구성됨
- 각 단계가 독립적인 객체로 캡슐화되지 않음
- 단계 추가/변경 시 전체 메서드 수정 필요

---

### 5. ⚠️ 테스트 가능성 저하

#### 문제: 인프라 의존성으로 인한 테스트 어려움
```java
private void checkAndRecoverPaymentStatusAfterTimeout(String userId, Long orderId) {
    Thread.sleep(1000); // ❌ 실제 대기 시간 필요
    paymentGatewaySchedulerClient.getTransactionsByOrder(...); // ❌ 외부 의존성
    updateOrderStatusByPaymentStatus(...); // ✅ 도메인 로직
}
```

**문제점:**
- `Thread.sleep`으로 인한 느린 테스트
- 외부 API 호출로 인한 통합 테스트 필요
- 단위 테스트 작성 어려움

---

## DDD 원칙 위반 사항

### 1. ❌ 계층 분리 원칙 위반
- **애플리케이션 계층**에 **도메인 로직**과 **인프라 로직**이 혼재
- **인프라 계층** 관심사(`Thread.sleep`, `FeignException` 처리)가 애플리케이션 계층에 존재

### 2. ❌ 도메인 모델 풍부도 부족
- 비즈니스 규칙(`isBusinessFailure`)이 도메인 모델이 아닌 애플리케이션 서비스에 존재
- 도메인 서비스로 분리되지 않음

### 3. ❌ 의존성 역전 원칙 (DIP) 위반
- 애플리케이션 서비스가 인프라 구현(`PaymentGatewayClient`)에 직접 의존
- 도메인 인터페이스를 통한 추상화 없음

---

## 개선 방안

### 1. ✅ PaymentGatewayAdapter 분리

**목적**: 인프라 관심사 분리

```java
@Component
public class PaymentGatewayAdapter {
    private final PaymentGatewayClient paymentGatewayClient;
    
    public PaymentResult requestPayment(PaymentRequest request) {
        try {
            PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
                paymentGatewayClient.requestPayment(request.userId(), toDto(request));
            
            return toDomainResult(response);
        } catch (FeignException e) {
            return handleFeignException(e, request);
        }
    }
    
    private PaymentResult handleFeignException(FeignException e, PaymentRequest request) {
        if (isTimeout(e)) {
            return PaymentResult.timeout(request.orderId());
        }
        if (isServerError(e)) {
            return PaymentResult.serverError(request.orderId());
        }
        return PaymentResult.clientError(request.orderId(), e.status());
    }
    
    private boolean isTimeout(FeignException e) {
        Throwable cause = e.getCause();
        return cause instanceof SocketTimeoutException || 
               cause instanceof TimeoutException ||
               (e.getMessage() != null && e.getMessage().contains("timeout"));
    }
}
```

**효과:**
- 인프라 예외 처리를 한 곳에 집중
- 애플리케이션 서비스는 도메인 모델만 다룸

---

### 2. ✅ PaymentFailureClassifier 도메인 서비스 분리

**목적**: 비즈니스 규칙을 도메인 서비스로 이동

```java
@Component
public class PaymentFailureClassifier {
    private static final Set<String> BUSINESS_FAILURE_CODES = Set.of(
        "LIMIT_EXCEEDED",
        "INVALID_CARD",
        "CARD_ERROR",
        "INSUFFICIENT_FUNDS",
        "PAYMENT_FAILED"
    );
    
    private static final String CIRCUIT_BREAKER_OPEN = "CIRCUIT_BREAKER_OPEN";
    
    public PaymentFailureType classify(String errorCode) {
        if (errorCode == null) {
            return PaymentFailureType.EXTERNAL_SYSTEM_FAILURE;
        }
        
        if (CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
            return PaymentFailureType.EXTERNAL_SYSTEM_FAILURE;
        }
        
        if (BUSINESS_FAILURE_CODES.stream().anyMatch(errorCode::contains)) {
            return PaymentFailureType.BUSINESS_FAILURE;
        }
        
        return PaymentFailureType.EXTERNAL_SYSTEM_FAILURE;
    }
}

public enum PaymentFailureType {
    BUSINESS_FAILURE,           // 주문 취소 필요
    EXTERNAL_SYSTEM_FAILURE     // 주문 PENDING 유지
}
```

**효과:**
- 비즈니스 규칙이 도메인 계층에 위치
- 다른 애플리케이션 서비스에서 재사용 가능
- 테스트 용이성 향상

---

### 3. ✅ PaymentRecoveryService 분리

**목적**: 타임아웃 복구 로직 분리 및 테스트 가능성 향상

```java
@Component
public class PaymentRecoveryService {
    private final PaymentGatewayAdapter paymentGatewayAdapter;
    private final OrderStatusUpdater orderStatusUpdater;
    private final DelayProvider delayProvider; // 테스트 가능하도록 인터페이스로 분리
    
    public void recoverAfterTimeout(String userId, Long orderId) {
        delayProvider.delay(Duration.ofSeconds(1)); // Thread.sleep 대신 인터페이스 사용
        
        PaymentStatus status = paymentGatewayAdapter.getPaymentStatus(userId, orderId);
        orderStatusUpdater.updateByPaymentStatus(orderId, status);
    }
}

// 테스트 가능하도록 인터페이스 분리
public interface DelayProvider {
    void delay(Duration duration) throws InterruptedException;
}

@Component
public class ThreadDelayProvider implements DelayProvider {
    @Override
    public void delay(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }
}
```

**효과:**
- `Thread.sleep`을 인터페이스로 추상화하여 테스트 가능
- 복구 로직을 독립적인 서비스로 분리

---

### 4. ✅ OrderStatusUpdater 도메인 서비스 분리

**목적**: 주문 상태 업데이트 로직을 도메인 서비스로 이동

```java
@Component
public class OrderStatusUpdater {
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final OrderCancellationService orderCancellationService;
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateByPaymentStatus(Long orderId, PaymentStatus paymentStatus) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        
        if (order.isCompletedOrCanceled()) {
            return; // 이미 처리됨
        }
        
        if (paymentStatus.isSuccess()) {
            order.complete();
            orderRepository.save(order);
        } else if (paymentStatus.isFailed()) {
            User user = userRepository.findById(order.getUserId())
                .orElseThrow(() -> new UserNotFoundException(order.getUserId()));
            orderCancellationService.cancel(order, user);
        }
        // PENDING 상태는 유지
    }
}
```

**효과:**
- 주문 상태 업데이트 로직이 도메인 서비스에 위치
- 도메인 지식이 도메인 계층에 집중

---

### 5. ✅ PaymentRequestBuilder 분리

**목적**: 요청 생성 로직 분리

```java
@Component
public class PaymentRequestBuilder {
    private final CallbackUrlGenerator callbackUrlGenerator;
    
    public PaymentRequest build(String userId, Long orderId, String cardType, 
                                String cardNo, Integer amount) {
        return PaymentRequest.builder()
            .userId(userId)
            .orderId(String.valueOf(orderId))
            .cardType(parseCardType(cardType))
            .cardNo(cardNo)
            .amount(amount.longValue())
            .callbackUrl(callbackUrlGenerator.generate(orderId))
            .build();
    }
    
    private CardType parseCardType(String cardType) {
        try {
            return CardType.valueOf(cardType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidCardTypeException(cardType);
        }
    }
}
```

**효과:**
- 요청 생성 로직이 독립적인 빌더로 분리
- 재사용성 향상

---

## 개선 후 구조

### 계층 구조
```
Application Layer (PurchasingFacade)
├── PaymentRequestBuilder (요청 생성)
├── PaymentGatewayAdapter (인프라 어댑터)
│   └── PaymentResult (도메인 모델)
├── PaymentFailureClassifier (도메인 서비스)
├── PaymentRecoveryService (복구 서비스)
│   └── DelayProvider (인프라 인터페이스)
└── OrderStatusUpdater (도메인 서비스)
    └── OrderCancellationService (도메인 서비스)
```

### 개선 후 `requestPaymentToGateway` 메서드
```java
private String requestPaymentToGateway(String userId, Long orderId, 
                                       String cardType, String cardNo, Integer amount) {
    PaymentRequest request = paymentRequestBuilder.build(
        userId, orderId, cardType, cardNo, amount
    );
    
    PaymentResult result = paymentGatewayAdapter.requestPayment(request);
    
    return result.handle(
        success -> {
            log.info("PG 결제 요청 성공. (orderId: {}, transactionKey: {})", 
                orderId, success.transactionKey());
            return success.transactionKey();
        },
        failure -> {
            PaymentFailureType failureType = paymentFailureClassifier.classify(failure.errorCode());
            
            if (failureType == PaymentFailureType.BUSINESS_FAILURE) {
                handlePaymentFailure(userId, orderId, failure.errorCode(), failure.message());
            } else if (failure.isTimeout()) {
                paymentRecoveryService.recoverAfterTimeout(userId, orderId);
            }
            // 외부 시스템 장애는 주문 PENDING 유지
            return null;
        }
    );
}
```

**개선 효과:**
- 메서드 길이: **127 라인 → 약 20 라인**
- 책임 분리: **8가지 책임 → 3가지 책임** (요청 생성, API 호출, 결과 처리)
- 테스트 가능성: **통합 테스트 → 단위 테스트** 가능

---

## 결론

### 현재 상태
- ❌ **절차지향적 코드**: 긴 메서드와 순차적 처리
- ❌ **책임 혼재**: 애플리케이션/도메인/인프라 로직이 한 곳에
- ❌ **테스트 어려움**: 인프라 의존성으로 인한 단위 테스트 불가

### 개선 후 기대 효과
- ✅ **객체지향적 설계**: 각 책임이 독립적인 객체로 분리
- ✅ **계층 분리**: 애플리케이션/도메인/인프라 계층 명확히 분리
- ✅ **테스트 용이성**: 각 컴포넌트를 독립적으로 테스트 가능
- ✅ **재사용성**: 도메인 서비스는 다른 애플리케이션 서비스에서도 사용 가능
- ✅ **유지보수성**: 변경 영향 범위 최소화

### 우선순위
1. **높음**: `PaymentFailureClassifier` 도메인 서비스 분리 (비즈니스 규칙)
2. **높음**: `PaymentGatewayAdapter` 분리 (인프라 관심사)
3. **중간**: `OrderStatusUpdater` 도메인 서비스 분리
4. **중간**: `PaymentRecoveryService` 분리 및 `DelayProvider` 인터페이스화
5. **낮음**: `PaymentRequestBuilder` 분리 (코드 정리)

