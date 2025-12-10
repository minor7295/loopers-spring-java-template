package com.loopers.application.purchasing;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.application.order.OrderService;
import com.loopers.domain.product.Product;
import com.loopers.application.product.ProductService;
import com.loopers.domain.user.PointEvent;
import com.loopers.domain.user.PointEventPublisher;
import com.loopers.domain.user.User;
import com.loopers.application.user.UserService;
import com.loopers.application.coupon.CouponService;
import com.loopers.infrastructure.payment.PaymentGatewayDto;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentEventPublisher;
import com.loopers.application.payment.PaymentService;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import feign.FeignException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 구매 파사드.
 * <p>
 * 주문 생성과 결제(포인트 차감), 재고 조정, 외부 연동을 조율하는 애플리케이션 서비스입니다.
 * 여러 도메인 서비스를 조합하여 구매 유즈케이스를 처리합니다.
 * </p>
 * <p>
 * <b>EDA 원칙 준수:</b>
 * <ul>
 *   <li><b>이벤트 기반:</b> 도메인 이벤트만 발행하고, 다른 애그리거트를 직접 수정하지 않음</li>
 *   <li><b>느슨한 결합:</b> Product, User, Payment 애그리거트와의 직접적인 의존성 최소화</li>
 *   <li><b>책임 분리:</b> 주문 도메인만 관리하고, 재고/포인트/결제 처리는 이벤트 핸들러에서 처리</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PurchasingFacade {

    private final UserService userService; // String userId를 Long id로 변환하는 데만 사용
    private final ProductService productService; // 상품 조회용으로만 사용 (재고 검증은 이벤트 핸들러에서)
    private final CouponService couponService; // 쿠폰 적용용으로만 사용
    private final OrderService orderService;
    private final PaymentService paymentService; // Payment 조회용으로만 사용
    private final PointEventPublisher pointEventPublisher; // PointEvent 발행용
    private final PaymentEventPublisher paymentEventPublisher; // PaymentEvent 발행용

    /**
     * 주문을 생성한다.
     * <p>
     * 1. 사용자 조회 및 존재 여부 검증<br>
     * 2. 상품 조회 (재고 검증은 이벤트 핸들러에서 처리)<br>
     * 3. 쿠폰 할인 적용<br>
     * 4. 주문 저장 및 OrderEvent.OrderCreated 이벤트 발행<br>
     * 5. 포인트 사용 시 PointEvent.PointUsed 이벤트 발행<br>
     * 6. 결제 요청 시 PaymentEvent.PaymentRequested 이벤트 발행<br>
     * </p>
     * <p>
     * <b>결제 방식:</b>
     * <ul>
     *   <li><b>포인트+쿠폰 전액 결제:</b> paidAmount == 0이면 PG 요청 없이 바로 완료</li>
     *   <li><b>혼합 결제:</b> 포인트 일부 사용 + PG 결제 나머지 금액</li>
     *   <li><b>카드만 결제:</b> 포인트 사용 없이 카드로 전체 금액 결제</li>
     * </ul>
     * </p>
     * <p>
     * <b>EDA 원칙:</b>
     * <ul>
     *   <li><b>이벤트 기반:</b> 재고 차감은 OrderEvent.OrderCreated를 구독하는 ProductEventHandler에서 처리</li>
     *   <li><b>이벤트 기반:</b> 포인트 차감은 PointEvent.PointUsed를 구독하는 PointEventHandler에서 처리</li>
     *   <li><b>이벤트 기반:</b> Payment 생성 및 PG 결제는 PaymentEvent.PaymentRequested를 구독하는 PaymentEventHandler에서 처리</li>
     *   <li><b>느슨한 결합:</b> Product, User, Payment 애그리거트를 직접 수정하지 않고 이벤트만 발행</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 식별자 (로그인 ID)
     * @param commands 주문 상품 정보
     * @param usedPoint 포인트 사용량 (선택, 기본값: 0)
     * @param cardType 카드 타입 (paidAmount > 0일 때만 필수)
     * @param cardNo 카드 번호 (paidAmount > 0일 때만 필수)
     * @return 생성된 주문 정보
     */
    @Transactional
    public OrderInfo createOrder(String userId, List<OrderItemCommand> commands, Long usedPoint, String cardType, String cardNo) {
        if (userId == null || userId.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자 ID는 필수입니다.");
        }
        if (commands == null || commands.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 아이템은 1개 이상이어야 합니다.");
        }

        // ✅ EDA 원칙: UserService는 String userId를 Long id로 변환하는 데만 사용
        // 포인트 검증은 PointEventHandler에서 처리
        User user = userService.getUser(userId);

        // ✅ EDA 원칙: ProductService는 상품 조회만 (재고 검증은 ProductEventHandler에서 처리)
        List<Long> sortedProductIds = commands.stream()
            .map(OrderItemCommand::productId)
            .distinct()
            .sorted()
            .toList();

        // 중복 상품 검증
        if (sortedProductIds.size() != commands.size()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품이 중복되었습니다.");
        }

        // 상품 조회 (재고 검증은 이벤트 핸들러에서 처리)
        Map<Long, Product> productMap = new java.util.HashMap<>();
        for (Long productId : sortedProductIds) {
            Product product = productService.getProduct(productId);
            productMap.put(productId, product);
        }

        // OrderItem 생성
        List<OrderItem> orderItems = new ArrayList<>();
        for (OrderItemCommand command : commands) {
            Product product = productMap.get(command.productId());
            orderItems.add(OrderItem.of(
                product.getId(),
                product.getName(),
                product.getPrice(),
                command.quantity()
            ));
        }

        // 쿠폰 처리 (있는 경우)
        String couponCode = extractCouponCode(commands);
        Integer subtotal = calculateSubtotal(orderItems);
        if (couponCode != null && !couponCode.isBlank()) {
            couponService.applyCoupon(user.getId(), couponCode, subtotal);
        }

        // 포인트 사용량
        Long usedPointAmount = Objects.requireNonNullElse(usedPoint, 0L);

        // ✅ OrderService.create() 호출 → OrderEvent.OrderCreated 이벤트 발행
        // ✅ ProductEventHandler가 OrderEvent.OrderCreated를 구독하여 재고 차감 처리
        Order savedOrder = orderService.create(user.getId(), orderItems, couponCode, subtotal, usedPointAmount);

        // ✅ 포인트 사용 시 PointEvent.PointUsed 이벤트 발행
        // ✅ PointEventHandler가 PointEvent.PointUsed를 구독하여 포인트 차감 처리
        if (usedPointAmount > 0) {
            pointEventPublisher.publish(PointEvent.PointUsed.of(
                savedOrder.getId(),
                user.getId(),
                usedPointAmount
            ));
        }

        // PG 결제 금액 계산
        Long totalAmount = savedOrder.getTotalAmount().longValue();
        Long paidAmount = totalAmount - usedPointAmount;

        // ✅ 결제 요청 시 PaymentEvent.PaymentRequested 이벤트 발행
        // ✅ PaymentEventHandler가 PaymentEvent.PaymentRequested를 구독하여 Payment 생성 및 PG 결제 요청 처리
        if (paidAmount == 0) {
            // 포인트+쿠폰으로 전액 결제 완료된 경우
            // PaymentEventHandler가 Payment를 생성하고 바로 완료 처리
            paymentEventPublisher.publish(PaymentEvent.PaymentRequested.of(
                savedOrder.getId(),
                userId,
                user.getId(),
                totalAmount,
                usedPointAmount,
                null,
                null
            ));
            log.debug("포인트+쿠폰으로 전액 결제 요청. (orderId: {})", savedOrder.getId());
        } else {
            // PG 결제가 필요한 경우
            if (cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
                throw new CoreException(ErrorType.BAD_REQUEST,
                    "포인트와 쿠폰만으로 결제할 수 없습니다. 카드 정보를 입력해주세요.");
            }

            paymentEventPublisher.publish(PaymentEvent.PaymentRequested.of(
                savedOrder.getId(),
                userId,
                user.getId(),
                totalAmount,
                usedPointAmount,
                cardType,
                cardNo
            ));
            log.debug("PG 결제 요청. (orderId: {})", savedOrder.getId());
        }

        return OrderInfo.from(savedOrder);
    }

    /**
     * 주문을 취소하고 포인트를 환불하며 재고를 원복한다.
     * <p>
     * <b>EDA 원칙:</b>
     * <ul>
     *   <li><b>이벤트 기반:</b> OrderService.cancelOrder()가 OrderEvent.OrderCanceled 이벤트를 발행</li>
     *   <li><b>이벤트 기반:</b> 재고 원복은 OrderEvent.OrderCanceled를 구독하는 ProductEventHandler에서 처리</li>
     *   <li><b>이벤트 기반:</b> 포인트 환불은 OrderEvent.OrderCanceled를 구독하는 PointEventHandler에서 처리</li>
     *   <li><b>느슨한 결합:</b> Product, User 애그리거트를 직접 수정하지 않고 이벤트만 발행</li>
     * </ul>
     * </p>
     *
     * @param order 주문 엔티티
     * @param user 사용자 엔티티
     */
    @Transactional
    public void cancelOrder(Order order, User user) {
        if (order == null || user == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "취소할 주문과 사용자 정보는 필수입니다.");
        }

        // 실제로 사용된 포인트만 환불 (Payment에서 확인)
        Long refundPointAmount = paymentService.getPaymentByOrderId(order.getId())
            .map(Payment::getUsedPoint)
            .orElse(0L);

        // ✅ OrderService.cancelOrder() 호출 → OrderEvent.OrderCanceled 이벤트 발행
        // ✅ ProductEventHandler가 OrderEvent.OrderCanceled를 구독하여 재고 원복 처리
        // ✅ PointEventHandler가 OrderEvent.OrderCanceled를 구독하여 포인트 환불 처리
        orderService.cancelOrder(order.getId(), "사용자 요청", refundPointAmount);
        
        log.info("주문 취소 처리 완료. (orderId: {}, refundPointAmount: {})", order.getId(), refundPointAmount);
    }

    /**
     * 사용자 ID로 주문 목록을 조회한다.
     *
     * @param userId 사용자 식별자 (로그인 ID)
     * @return 주문 목록
     */
    @Transactional(readOnly = true)
    public List<OrderInfo> getOrders(String userId) {
        User user = userService.getUser(userId);
        List<Order> orders = orderService.getOrdersByUserId(user.getId());
        return orders.stream()
            .map(OrderInfo::from)
            .toList();
    }

    /**
     * 주문 ID로 단일 주문을 조회한다.
     *
     * @param userId 사용자 식별자 (로그인 ID)
     * @param orderId 주문 ID
     * @return 주문 정보
     */
    @Transactional(readOnly = true)
    public OrderInfo getOrder(String userId, Long orderId) {
        User user = userService.getUser(userId);
        Order order = orderService.getById(orderId);

        if (!order.getUserId().equals(user.getId())) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }

        return OrderInfo.from(order);
    }



    /**
     * 주문 명령에서 쿠폰 코드를 추출합니다.
     *
     * @param commands 주문 명령 목록
     * @return 쿠폰 코드 (없으면 null)
     */
    private String extractCouponCode(List<OrderItemCommand> commands) {
        return commands.stream()
            .filter(cmd -> cmd.couponCode() != null && !cmd.couponCode().isBlank())
            .map(OrderItemCommand::couponCode)
            .findFirst()
            .orElse(null);
    }


    /**
     * 주문 아이템 목록으로부터 소계 금액을 계산합니다.
     *
     * @param orderItems 주문 아이템 목록
     * @return 계산된 소계 금액
     */
    private Integer calculateSubtotal(List<OrderItem> orderItems) {
        return orderItems.stream()
            .mapToInt(item -> item.getPrice() * item.getQuantity())
            .sum();
    }


    /**
     * PaymentGatewayDto.TransactionStatus를 PaymentStatus 도메인 모델로 변환합니다.
     *
     * @param transactionStatus 인프라 계층의 TransactionStatus
     * @return 도메인 모델의 PaymentStatus
     */
    private PaymentStatus convertToPaymentStatus(
        PaymentGatewayDto.TransactionStatus transactionStatus
    ) {
        return switch (transactionStatus) {
            case SUCCESS -> PaymentStatus.SUCCESS;
            case FAILED -> PaymentStatus.FAILED;
            case PENDING -> PaymentStatus.PENDING;
        };
    }
    
    /**
     * PaymentStatus 도메인 모델을 PaymentGatewayDto.TransactionStatus로 변환합니다.
     *
     * @param paymentStatus 도메인 모델의 PaymentStatus
     * @return 인프라 계층의 TransactionStatus
     */
    private PaymentGatewayDto.TransactionStatus convertToInfraStatus(PaymentStatus paymentStatus) {
        return switch (paymentStatus) {
            case SUCCESS -> PaymentGatewayDto.TransactionStatus.SUCCESS;
            case FAILED -> PaymentGatewayDto.TransactionStatus.FAILED;
            case PENDING -> PaymentGatewayDto.TransactionStatus.PENDING;
        };
    }

    /**
     * 결제 상태에 따라 주문 상태를 업데이트합니다.
     * <p>
     * 별도 트랜잭션으로 실행하여 외부 시스템 호출과 독립적으로 처리합니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param paymentStatus 결제 상태 (도메인 모델)
     * @param transactionKey 트랜잭션 키
     * @param reason 실패 사유 (실패 시)
     * @return 업데이트 성공 여부 (true: 성공, false: 실패)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean updateOrderStatusByPaymentResult(
        Long orderId,
        PaymentStatus paymentStatus,
        String transactionKey,
        String reason
    ) {
        try {
            Order order = orderService.getOrder(orderId).orElse(null);
            
            if (order == null) {
                log.warn("주문 상태 업데이트 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
                return false;
            }
            
            // 이미 완료되거나 취소된 주문인 경우 처리하지 않음 (정상적인 경우이므로 true 반환)
            if (order.isCompleted()) {
                log.debug("이미 완료된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return true;
            }
            
            if (order.isCanceled()) {
                log.debug("이미 취소된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return true;
            }
            
            if (paymentStatus == PaymentStatus.SUCCESS) {
                // 결제 성공: 주문 완료
                orderService.updateStatusByPaymentResult(order, paymentStatus);
                log.info("결제 상태 확인 결과, 주문 상태를 COMPLETED로 업데이트했습니다. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
                return true;
            } else if (paymentStatus == PaymentStatus.FAILED) {
                // 결제 실패: 주문 취소 및 리소스 원복
                User user = userService.getUserById(order.getUserId());
                if (user == null) {
                    log.warn("주문 상태 업데이트 시 사용자를 찾을 수 없습니다. (orderId: {}, userId: {})",
                        orderId, order.getUserId());
                    return false;
                }
                cancelOrder(order, user);
                log.info("결제 상태 확인 결과, 주문 상태를 CANCELED로 업데이트했습니다. (orderId: {}, transactionKey: {}, reason: {})",
                    orderId, transactionKey, reason);
                return true;
            } else {
                // PENDING 상태: 아직 처리 중 (정상적인 경우이므로 true 반환)
                log.debug("결제 상태 확인 결과, 아직 처리 중입니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
                return true;
            }
        } catch (Exception e) {
            log.error("주문 상태 업데이트 중 오류 발생. (orderId: {})", orderId, e);
            return false;
        }
    }



    /**
     * PG 결제 콜백을 처리합니다.
     * <p>
     * PG 시스템에서 결제 처리 완료 후 콜백으로 전송된 결제 결과를 받아
     * 주문 상태를 업데이트합니다.
     * </p>
     * <p>
     * <b>보안 및 정합성 강화:</b>
     * <ul>
     *   <li>콜백 정보를 직접 신뢰하지 않고 PG 조회 API로 교차 검증</li>
     *   <li>불일치 시 PG 원장을 우선시하여 처리</li>
     *   <li>콜백 정보와 PG 조회 결과가 일치하는지 검증</li>
     * </ul>
     * </p>
     * <p>
     * <b>처리 내용:</b>
     * <ul>
     *   <li>결제 성공 (SUCCESS): 주문 상태를 COMPLETED로 변경</li>
     *   <li>결제 실패 (FAILED): 주문 상태를 CANCELED로 변경하고 리소스 원복</li>
     *   <li>결제 대기 (PENDING): 상태 유지 (추가 처리 없음)</li>
     * </ul>
     * </p>
     *
     * @param orderId 주문 ID
     * @param callbackRequest 콜백 요청 정보
     */
    @Transactional
    public void handlePaymentCallback(Long orderId, PaymentGatewayDto.CallbackRequest callbackRequest) {
        try {
            // 주문 조회
            Order order;
            try {
                order = orderService.getById(orderId);
            } catch (CoreException e) {
                log.warn("콜백 처리 시 주문을 찾을 수 없습니다. (orderId: {}, transactionKey: {})",
                    orderId, callbackRequest.transactionKey());
                return;
            }
            
            // 이미 완료되거나 취소된 주문인 경우 처리하지 않음
            if (order.isCompleted()) {
                log.debug("이미 완료된 주문입니다. 콜백 처리를 건너뜁니다. (orderId: {}, transactionKey: {})",
                    orderId, callbackRequest.transactionKey());
                return;
            }
            
            if (order.isCanceled()) {
                log.debug("이미 취소된 주문입니다. 콜백 처리를 건너뜁니다. (orderId: {}, transactionKey: {})",
                    orderId, callbackRequest.transactionKey());
                return;
            }
            
            // 콜백 정보와 PG 원장 교차 검증
            // 보안 및 정합성을 위해 PG 조회 API로 실제 결제 상태 확인
            PaymentGatewayDto.TransactionStatus verifiedStatus = verifyCallbackWithPgInquiry(
                order.getUserId(), orderId, callbackRequest);
            
            // PaymentService를 통한 콜백 처리 (도메인 모델로 변환)
            PaymentStatus paymentStatus = convertToPaymentStatus(verifiedStatus);
            paymentService.handleCallback(
                orderId,
                callbackRequest.transactionKey(),
                paymentStatus,
                callbackRequest.reason()
            );
            
            // 주문 상태 업데이트 처리
            boolean updated = updateOrderStatusByPaymentResult(
                orderId,
                paymentStatus,
                callbackRequest.transactionKey(),
                callbackRequest.reason()
            );
            
            if (updated) {
                log.info("PG 결제 콜백 처리 완료 (PG 원장 검증 완료). (orderId: {}, transactionKey: {}, status: {})",
                    orderId, callbackRequest.transactionKey(), verifiedStatus);
            } else {
                log.warn("PG 결제 콜백 처리 실패. 주문 상태 업데이트에 실패했습니다. (orderId: {}, transactionKey: {}, status: {})",
                    orderId, callbackRequest.transactionKey(), verifiedStatus);
            }
        } catch (Exception e) {
            log.error("콜백 처리 중 오류 발생. (orderId: {}, transactionKey: {})",
                orderId, callbackRequest.transactionKey(), e);
            throw e; // 콜백 실패는 재시도 가능하도록 예외를 다시 던짐
        }
    }

    /**
     * 콜백 정보를 PG 조회 API로 교차 검증합니다.
     * <p>
     * 보안 및 정합성을 위해 콜백 정보를 직접 신뢰하지 않고,
     * PG 원장(조회 API)을 기준으로 검증합니다.
     * </p>
     * <p>
     * <b>검증 전략:</b>
     * <ul>
     *   <li>PG 조회 API로 실제 결제 상태 확인</li>
     *   <li>콜백 정보와 PG 조회 결과 비교</li>
     *   <li>불일치 시 PG 원장을 우선시하여 처리</li>
     *   <li>PG 조회 실패 시 콜백 정보를 사용하되 경고 로그 기록</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID (Long)
     * @param orderId 주문 ID
     * @param callbackRequest 콜백 요청 정보
     * @return 검증된 결제 상태 (PG 원장 기준)
     */
    private PaymentGatewayDto.TransactionStatus verifyCallbackWithPgInquiry(
        Long userId, Long orderId, PaymentGatewayDto.CallbackRequest callbackRequest) {
        
        try {
            // User의 userId (String)를 가져오기 위해 User 조회
            User user;
            try {
                user = userService.getUserById(userId);
            } catch (CoreException e) {
                log.warn("콜백 검증 시 사용자를 찾을 수 없습니다. 콜백 정보를 사용합니다. (orderId: {}, userId: {})",
                    orderId, userId);
                return callbackRequest.status(); // 사용자를 찾을 수 없으면 콜백 정보 사용
            }
            
            String userIdString = user.getUserId();
            
            // PaymentService를 통한 결제 상태 조회 (PG 원장 기준)
            PaymentStatus paymentStatus = paymentService.getPaymentStatus(userIdString, orderId);
            
            // 도메인 모델을 인프라 DTO로 변환 (검증 로직에서 사용)
            PaymentGatewayDto.TransactionStatus pgStatus = convertToInfraStatus(paymentStatus);
            PaymentGatewayDto.TransactionStatus callbackStatus = callbackRequest.status();
            
            // 콜백 정보와 PG 조회 결과 비교
            if (pgStatus != callbackStatus) {
                // 불일치 시 PG 원장을 우선시하여 처리
                log.warn("콜백 정보와 PG 원장이 불일치합니다. PG 원장을 우선시하여 처리합니다. " +
                    "(orderId: {}, transactionKey: {}, 콜백 상태: {}, PG 원장 상태: {})",
                    orderId, callbackRequest.transactionKey(), callbackStatus, pgStatus);
                return pgStatus; // PG 원장 기준으로 처리
            }
            
            // 일치하는 경우: 정상 처리
            log.debug("콜백 정보와 PG 원장이 일치합니다. (orderId: {}, transactionKey: {}, 상태: {})",
                orderId, callbackRequest.transactionKey(), pgStatus);
            return pgStatus;
            
        } catch (FeignException e) {
            // PG 조회 API 호출 실패: 콜백 정보를 사용하되 경고 로그 기록
            log.warn("콜백 검증 시 PG 조회 API 호출 중 Feign 예외 발생. 콜백 정보를 사용합니다. " +
                "(orderId: {}, transactionKey: {}, status: {})",
                orderId, callbackRequest.transactionKey(), e.status(), e);
            return callbackRequest.status();
        } catch (Exception e) {
            // 기타 예외: 콜백 정보를 사용하되 경고 로그 기록
            log.warn("콜백 검증 시 예상치 못한 오류 발생. 콜백 정보를 사용합니다. " +
                "(orderId: {}, transactionKey: {})",
                orderId, callbackRequest.transactionKey(), e);
            return callbackRequest.status();
        }
    }

    /**
     * 결제 상태 확인 API를 통해 주문 상태를 복구합니다.
     * <p>
     * 콜백이 오지 않았거나 타임아웃된 경우, PG 시스템의 결제 상태 확인 API를 호출하여
     * 실제 결제 상태를 확인하고 주문 상태를 업데이트합니다.
     * </p>
     *
     * @param userId 사용자 ID (String - PG API 요구사항)
     * @param orderId 주문 ID
     */
    @Transactional
    public void recoverOrderStatusByPaymentCheck(String userId, Long orderId) {
        try {
            // PaymentService를 통한 타임아웃 복구
            paymentService.recoverAfterTimeout(userId, orderId);
            
            // 결제 상태 조회
            PaymentStatus paymentStatus = paymentService.getPaymentStatus(userId, orderId);
            
            // 주문 상태 업데이트 처리
            boolean updated = updateOrderStatusByPaymentResult(orderId, paymentStatus, null, null);
            
            if (!updated) {
                log.warn("상태 복구 실패. 주문 상태 업데이트에 실패했습니다. (orderId: {})", orderId);
            }
            
        } catch (Exception e) {
            log.error("상태 복구 중 오류 발생. (orderId: {})", orderId, e);
            // 기타 오류도 로그만 기록
        }
    }

}

