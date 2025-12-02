# 결제 콜백 및 상태 복구 메커니즘 검증 보고서

## 요구사항 확인

### 1. ✅ 결제 요청 → 처리 후 콜백 API 호출

**구현 위치**: `PurchasingFacade.requestPaymentToGateway()`

**구현 내용**:
```java
// 콜백 URL 생성 (주문 ID 기반)
String callbackUrl = String.format("http://localhost:8080/api/v1/orders/%d/callback", orderId);

// PG 결제 요청
PaymentGatewayDto.PaymentRequest request = new PaymentGatewayDto.PaymentRequest(
    String.valueOf(orderId),
    gatewayCardType,
    cardNo,
    amount.longValue(),
    callbackUrl  // 콜백 URL 포함
);
```

**콜백 엔드포인트**: `POST /api/v1/orders/{orderId}/callback`
- **위치**: `PurchasingV1Controller.handlePaymentCallback()`
- **처리 메서드**: `PurchasingFacade.handlePaymentCallback()`

**검증 결과**: ✅ **완벽하게 구현됨**
- 결제 요청 시 콜백 URL이 포함됨
- 콜백 엔드포인트가 구현되어 있음
- 콜백 처리 로직이 완전히 구현됨

---

### 2. ✅ 콜백과 orderId 기반 조회 API 둘다 활용

#### 2.1 콜백 활용
**구현 위치**: `PurchasingFacade.handlePaymentCallback()`

**처리 내용**:
- 결제 성공 (SUCCESS): 주문 상태를 COMPLETED로 변경
- 결제 실패 (FAILED): 주문 상태를 CANCELED로 변경하고 리소스 원복
- 결제 대기 (PENDING): 상태 유지

#### 2.2 orderId 기반 조회 API 활용
**구현 위치**: `PurchasingFacade.recoverOrderStatusByPaymentCheck()`

**사용 API**: `PaymentGatewayClient.getTransactionsByOrder()`
```java
// PG에서 주문별 결제 정보 조회
PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
    paymentGatewayClient.getTransactionsByOrder(userId, String.valueOf(orderId));
```

**활용 시나리오**:
1. **타임아웃 발생 시**: `checkAndRecoverPaymentStatusAfterTimeout()`에서 사용 (line 620)
2. **수동 복구**: `recoverOrderStatusByPaymentCheck()`에서 사용 (line 877)
3. **스케줄러 복구**: `PaymentRecoveryScheduler`에서 사용

**검증 결과**: ✅ **완벽하게 구현됨**
- 콜백과 orderId 기반 조회 API가 모두 활용됨
- 각각의 용도가 명확히 구분됨
- 두 메커니즘이 상호 보완적으로 동작함

---

### 3. ✅ 콜백이 항상 온다는 보장이 없음 → 상태 보정 필요

#### 3.1 자동 상태 보정 메커니즘

**1. 주기적 스케줄러 복구**
- **구현 위치**: `PaymentRecoveryScheduler.recoverPendingOrders()`
- **실행 주기**: 1분마다 (`@Scheduled(fixedDelay = 60000)`)
- **처리 내용**:
  - PENDING 상태인 주문들을 조회
  - 각 주문에 대해 `getTransactionsByOrder()` 호출
  - 결제 상태에 따라 주문 상태 업데이트

**2. 타임아웃 시 즉시 상태 확인**
- **구현 위치**: `PurchasingFacade.checkAndRecoverPaymentStatusAfterTimeout()`
- **트리거**: 결제 요청 타임아웃 발생 시
- **처리 내용**:
  - 1초 대기 후 (PG 처리 시간 고려)
  - `getTransactionsByOrder()` 호출하여 즉시 상태 확인
  - 결제 상태에 따라 주문 상태 업데이트

#### 3.2 수동 상태 보정 메커니즘

**수동 복구 API**: `POST /api/v1/orders/{orderId}/recover`
- **위치**: `PurchasingV1Controller.recoverOrderStatus()`
- **처리 메서드**: `PurchasingFacade.recoverOrderStatusByPaymentCheck()`
- **용도**: 관리자나 사용자가 수동으로 상태 복구 요청

**검증 결과**: ✅ **완벽하게 구현됨**
- 콜백이 오지 않을 경우를 대비한 다층 보정 메커니즘 구현
- 자동 복구 (스케줄러 + 타임아웃 시 즉시 확인)
- 수동 복구 (API 엔드포인트)
- Eventually Consistent 패턴 적용

---

## 전체 플로우 다이어그램

```
[결제 요청]
    ↓
[PG 시스템에 결제 요청 + 콜백 URL 포함]
    ↓
    ├─→ [콜백 수신] ─→ [handlePaymentCallback] ─→ [주문 상태 업데이트] ✅
    │
    ├─→ [타임아웃 발생] ─→ [즉시 상태 확인] ─→ [getTransactionsByOrder] ─→ [주문 상태 업데이트] ✅
    │
    └─→ [콜백 미수신] ─→ [스케줄러 (1분마다)] ─→ [getTransactionsByOrder] ─→ [주문 상태 업데이트] ✅
                                                      ↑
                                    [수동 복구 API] ──┘
```

---

## 검증 요약

| 요구사항 | 구현 상태 | 위치 |
|---------|---------|------|
| 결제 요청 시 콜백 URL 포함 | ✅ 완료 | `PurchasingFacade.requestPaymentToGateway()` (line 480-489) |
| 콜백 엔드포인트 구현 | ✅ 완료 | `PurchasingV1Controller.handlePaymentCallback()` (line 87-94) |
| 콜백 처리 로직 | ✅ 완료 | `PurchasingFacade.handlePaymentCallback()` (line 783-832) |
| orderId 기반 조회 API 활용 | ✅ 완료 | `PaymentGatewayClient.getTransactionsByOrder()` (line 59-62) |
| 타임아웃 시 즉시 상태 확인 | ✅ 완료 | `PurchasingFacade.checkAndRecoverPaymentStatusAfterTimeout()` (line 611-680) |
| 주기적 스케줄러 복구 | ✅ 완료 | `PaymentRecoveryScheduler.recoverPendingOrders()` (line 70-117) |
| 수동 복구 API | ✅ 완료 | `PurchasingV1Controller.recoverOrderStatus()` (line 103-110) |

---

## 결론

**모든 요구사항이 완벽하게 구현되어 있습니다.**

1. ✅ **결제 요청 → 처리 후 콜백 API 호출**: 콜백 URL이 결제 요청에 포함되고, 콜백 엔드포인트가 구현됨
2. ✅ **콜백과 orderId 기반 조회 API 둘다 활용**: 두 메커니즘이 모두 구현되어 상호 보완적으로 동작
3. ✅ **콜백이 항상 온다는 보장이 없음 → 상태 보정 필요**: 
   - 자동 복구: 스케줄러 (1분마다) + 타임아웃 시 즉시 확인
   - 수동 복구: API 엔드포인트 제공
   - Eventually Consistent 패턴 적용

**추가 개선 사항**:
- 현재 구현이 요구사항을 완벽하게 충족함
- 모든 시나리오에 대한 복구 메커니즘이 구현됨
- 테스트 코드도 충분히 작성되어 있음

