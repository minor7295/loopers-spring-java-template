# PG 응답 순서 뒤집힘 정합성 처리 검증 보고서

## 가치평가 핵심 원칙

> **"함부로 성공/실패를 단정하지 말라. 상태는 항상 PG 조회로 최종 결정된다."**

### 핵심 전략
1. **fallback 응답에서는 절대 성공/실패로 단정하지 말기**
2. **"결제 중(PENDING)" 상태가 안전한 기본값**
3. **업데이트 시 updatedAt 타임스탬프 기반으로 최신 상태만 유지**
4. **최신 데이터보다 오래된 값이 오면 기록하지 않음**
5. **모든 상태 전이는 PG 원장(DB) → PG 조회 API만을 신뢰**

---

## 현재 구현 상태 분석

### 1. ✅ Fallback 응답에서 성공/실패 단정하지 않음

**위치**: `PaymentGatewayClientFallback.java`, `PurchasingFacade.requestPaymentToGateway()`

**구현 내용**:
```java
// Fallback 응답: CIRCUIT_BREAKER_OPEN 에러 코드 반환
return new PaymentGatewayDto.ApiResponse<>(
    new PaymentGatewayDto.ApiResponse.Metadata(
        PaymentGatewayDto.ApiResponse.Metadata.Result.FAIL,
        "CIRCUIT_BREAKER_OPEN",
        "PG 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
    ),
    null
);

// PurchasingFacade에서 처리
if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
    log.info("CircuitBreaker가 Open 상태입니다. Fallback이 호출되었습니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
    return null; // 주문은 PENDING 상태로 유지
}
```

**검증 결과**: ✅ **완벽하게 구현됨**
- Fallback 응답은 CIRCUIT_BREAKER_OPEN 에러 코드만 반환
- 성공/실패를 단정하지 않고 주문을 PENDING 상태로 유지
- 나중에 PG 조회 API로 최종 상태 확인 가능

---

### 2. ✅ PENDING 상태가 안전한 기본값

**위치**: `Order.java`, `PurchasingFacade.requestPaymentToGateway()`

**구현 내용**:
```java
// Order 생성 시 PENDING 상태로 초기화
this.status = OrderStatus.PENDING;

// 결제 요청 실패 시에도 PENDING 상태 유지
if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
    return null; // 주문은 PENDING 상태로 유지
}

// 외부 시스템 장애 시에도 PENDING 상태 유지
log.info("외부 시스템 장애로 인한 실패. 주문은 PENDING 상태로 유지됩니다. (orderId: {}, errorCode: {})",
    orderId, errorCode);
```

**검증 결과**: ✅ **완벽하게 구현됨**
- 주문 생성 시 PENDING 상태로 초기화
- 결제 요청 실패 시에도 PENDING 상태 유지
- 타임아웃 발생 시에도 PENDING 상태 유지
- 안전한 기본값으로 PENDING 사용

---

### 3. ⚠️ updatedAt 타임스탬프 기반 최신 상태만 유지 - 부분 구현

**위치**: `BaseEntity.java`, `Order.java`

**현재 상태**:
- `Order` 엔티티는 `BaseEntity`를 상속하여 `updatedAt` 필드가 있음
- `@PreUpdate`로 `updatedAt`이 자동 업데이트됨

**문제점**:
- ⚠️ 콜백 처리 시 `updatedAt`을 비교하여 최신 상태만 유지하는 로직이 없음
- ⚠️ 조회 API 응답 시 `updatedAt`을 비교하여 오래된 데이터를 무시하는 로직이 없음
- ⚠️ PG 응답에 타임스탬프 정보가 포함되어 있지 않음 (확인 필요)

**검증 결과**: ⚠️ **부분적으로 구현됨**
- `updatedAt` 필드는 존재하지만, 최신 상태만 유지하는 로직이 없음
- 콜백이나 조회 API에서 타임스탬프 비교 로직이 없음

---

### 4. ⚠️ 최신 데이터보다 오래된 값이 오면 기록하지 않음 - 미구현

**위치**: `PurchasingFacade.handlePaymentCallback()`, `PurchasingFacade.recoverOrderStatusByPaymentCheck()`

**현재 구현**:
```java
// 이미 완료되거나 취소된 주문인 경우 처리하지 않음
if (order.getStatus() == OrderStatus.COMPLETED) {
    log.info("이미 완료된 주문입니다. 콜백 처리를 건너뜁니다. (orderId: {}, transactionKey: {})",
        orderId, callbackRequest.transactionKey());
    return;
}

if (order.getStatus() == OrderStatus.CANCELED) {
    log.info("이미 취소된 주문입니다. 콜백 처리를 건너뜁니다. (orderId: {}, transactionKey: {})",
        orderId, callbackRequest.transactionKey());
    return;
}
```

**문제점**:
- ⚠️ 상태 기반으로만 체크 (COMPLETED/CANCELED)
- ⚠️ 타임스탬프 기반으로 오래된 데이터를 무시하는 로직이 없음
- ⚠️ PENDING → COMPLETED로 변경된 후, 오래된 FAILED 콜백이 오면 무시하지 않음

**검증 결과**: ⚠️ **부분적으로 구현됨**
- 최종 상태(COMPLETED/CANCELED)로 전이된 경우만 무시
- 타임스탬프 기반으로 오래된 데이터를 무시하는 로직은 없음

---

### 5. ✅ 모든 상태 전이는 PG 조회 API만을 신뢰

**위치**: `PurchasingFacade.recoverOrderStatusByPaymentCheck()`

**구현 내용**:
```java
// PG에서 주문별 결제 정보 조회
PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
    paymentGatewayClient.getTransactionsByOrder(userId, String.valueOf(orderId));

// 가장 최근 트랜잭션의 상태 확인
PaymentGatewayDto.TransactionResponse latestTransaction = 
    response.data().transactions().get(response.data().transactions().size() - 1);

PaymentGatewayDto.TransactionStatus status = latestTransaction.status();

if (status == PaymentGatewayDto.TransactionStatus.SUCCESS) {
    // 결제 성공: 주문 완료
    order.complete();
    orderRepository.save(order);
} else if (status == PaymentGatewayDto.TransactionStatus.FAILED) {
    // 결제 실패: 주문 취소 및 리소스 원복
    cancelOrder(order, user);
}
```

**검증 결과**: ✅ **완벽하게 구현됨**
- 타임아웃 발생 시 PG 조회 API로 상태 확인
- 스케줄러에서 PG 조회 API로 상태 복구
- PG 조회 API를 최종 결정으로 사용

---

## 문제점 및 개선 방안

### ⚠️ 문제 1: 타임스탬프 기반 최신 상태만 유지 로직 부재

**현재 상황**:
- 콜백이나 조회 API 응답에 타임스탬프 정보가 없음
- `updatedAt` 필드는 있지만 비교 로직이 없음
- 오래된 콜백이 늦게 도착해도 상태를 덮어쓸 수 있음

**개선 방안**:
1. **PG 응답에 타임스탬프 추가** (PG 시스템 변경 필요)
   - 콜백 요청에 `updatedAt` 또는 `transactionTime` 필드 추가
   - 조회 API 응답에 트랜잭션 타임스탬프 추가

2. **타임스탬프 비교 로직 추가**
   ```java
   // 콜백 처리 시
   if (callbackRequest.updatedAt() != null && order.getUpdatedAt() != null) {
       if (callbackRequest.updatedAt().isBefore(order.getUpdatedAt())) {
           log.warn("오래된 콜백을 무시합니다. (orderId: {}, callbackTime: {}, orderUpdatedAt: {})",
               orderId, callbackRequest.updatedAt(), order.getUpdatedAt());
           return;
       }
   }
   ```

3. **조회 API에서 최신 트랜잭션 선택**
   - 현재는 리스트의 마지막 요소를 선택
   - 타임스탬프 기반으로 최신 트랜잭션 선택하도록 개선

### ⚠️ 문제 2: 콜백과 조회 API의 순서 꼬임 처리 부족

**현재 상황**:
- 콜백이 늦게 도착해도 상태 기반으로만 체크
- PENDING → COMPLETED로 변경된 후, 오래된 FAILED 콜백이 오면 무시하지 않음 (현재는 COMPLETED 상태이므로 무시됨)
- 하지만 PENDING 상태에서 오래된 콜백이 오면 덮어쓸 수 있음

**개선 방안**:
- 타임스탬프 기반 비교 로직 추가
- PG 원장(DB)의 타임스탬프를 기준으로 최신 상태만 유지

---

## 검증 요약

| 항목 | 가치평가 요구사항 | 현재 구현 | 평가 |
|------|-----------------|---------|------|
| Fallback 응답에서 성공/실패 단정하지 않음 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| PENDING 상태가 안전한 기본값 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |
| updatedAt 타임스탬프 기반 최신 상태만 유지 | ✅ 필수 | ⚠️ 부분적 | ⚠️ 개선 필요 |
| 오래된 값이 오면 기록하지 않음 | ✅ 필수 | ⚠️ 부분적 | ⚠️ 개선 필요 |
| 모든 상태 전이는 PG 조회 API만을 신뢰 | ✅ 필수 | ✅ 구현됨 | ✅ 완벽 |

---

## 결론

**현재 구현 상태**:
- ✅ Fallback 응답에서 성공/실패 단정하지 않음: 완벽하게 구현됨
- ✅ PENDING 상태가 안전한 기본값: 완벽하게 구현됨
- ✅ 모든 상태 전이는 PG 조회 API만을 신뢰: 완벽하게 구현됨
- ⚠️ updatedAt 타임스탬프 기반 최신 상태만 유지: 부분적으로 구현됨
- ⚠️ 오래된 값이 오면 기록하지 않음: 부분적으로 구현됨

**가치평가 준수도**: **60%**

**핵심 가치관 반영**:
- ✅ "함부로 성공/실패를 단정하지 말라" - Fallback 응답에서 PENDING 상태 유지
- ✅ "상태는 항상 PG 조회로 최종 결정된다" - PG 조회 API를 최종 결정으로 사용
- ⚠️ "최신 데이터보다 오래된 값이 오면 기록하지 않음" - 타임스탬프 기반 비교 로직 부재

**개선 권장 사항**:
1. **PG 응답에 타임스탬프 추가** (PG 시스템 변경 필요)
2. **타임스탬프 기반 비교 로직 추가** (콜백 및 조회 API 처리 시)
3. **최신 트랜잭션 선택 로직 개선** (타임스탬프 기반)

**현재 구현의 장점**:
- 상태 기반으로 최종 상태(COMPLETED/CANCELED)로 전이된 경우는 무시
- PENDING 상태를 안전한 기본값으로 사용
- PG 조회 API를 최종 결정으로 사용

**현재 구현의 한계**:
- 타임스탬프 기반으로 오래된 데이터를 무시하는 로직이 없음
- 콜백과 조회 API의 순서가 꼬인 경우를 완벽하게 처리하지 못함

