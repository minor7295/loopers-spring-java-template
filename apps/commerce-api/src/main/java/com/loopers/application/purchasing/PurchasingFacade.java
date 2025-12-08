package com.loopers.application.purchasing;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import com.loopers.domain.coupon.CouponService;
import com.loopers.infrastructure.payment.PaymentGatewayDto;
import com.loopers.domain.payment.PaymentRequestResult;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.CardType;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import feign.FeignException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 구매 파사드.
 * <p>
 * 주문 생성과 결제(포인트 차감), 재고 조정, 외부 연동을 조율한다.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PurchasingFacade {

    private final UserService userService;
    private final ProductService productService;
    private final CouponService couponService;
    private final OrderService orderService;
    private final PaymentService paymentService;  // Payment 관련: PaymentService만 의존 (DIP 준수)
    private final PlatformTransactionManager transactionManager;

    /**
     * 주문을 생성한다.
     * <p>
     * 1. 사용자 조회 및 존재 여부 검증<br>
     * 2. 상품 재고 검증 및 차감<br>
     * 3. 쿠폰 할인 적용<br>
     * 4. 사용자 포인트 차감 (지정된 금액만)<br>
     * 5. 주문 저장<br>
     * 6. Payment 생성 (포인트+쿠폰 혼합 지원)<br>
     * 7. PG 결제 금액이 0이면 바로 완료, 아니면 PG 결제 요청 (비동기)
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
     * <b>동시성 제어 전략:</b>
     * <ul>
     *   <li><b>PESSIMISTIC_WRITE 사용 근거:</b> Lost Update 방지 및 데이터 일관성 보장</li>
     *   <li><b>포인트 차감:</b> 동시 주문 시 포인트 중복 차감 방지 (금전적 손실 방지)</li>
     *   <li><b>재고 차감:</b> 동시 주문 시 재고 음수 방지 및 정확한 차감 보장 (재고 oversell 방지)</li>
     *   <li><b>Lock 범위 최소화:</b> PK/UNIQUE 인덱스 기반 조회로 Lock 범위 최소화</li>
     * </ul>
     * </p>
     * <p>
     * <b>DBA 설득 근거 (비관적 락 사용):</b>
     * <ul>
     *   <li><b>제한적 사용:</b> 전역이 아닌 금전적 손실 위험이 있는 특정 도메인에만 사용</li>
     *   <li><b>트랜잭션 최소화:</b> 트랜잭션 내부에 외부 I/O 없음, lock holding time 매우 짧음 (몇 ms)</li>
     *   <li><b>Lock 범위 최소화:</b> PK/UNIQUE 인덱스 기반 조회로 해당 행만 락 (Record Lock)</li>
     *   <li><b>애플리케이션 레벨 한계:</b> 애플리케이션 레벨로는 race condition을 완전히 방지할 수 없어서 DB 차원의 strong consistency 필요</li>
     *   <li><b>낙관적 락 기본 전략:</b> 쿠폰 사용은 낙관적 락 사용 (Hot Spot 대응)</li>
     * </ul>
     * </p>
     * <p>
     * <b>Lock 생명주기:</b>
     * <ol>
     *   <li>SELECT ... FOR UPDATE 실행 시 락 획득</li>
     *   <li>트랜잭션 내에서 락 유지 (외부 I/O 없음, 매우 짧은 시간)</li>
     *   <li>트랜잭션 커밋/롤백 시 락 자동 해제</li>
     * </ol>
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

        // 비관적 락을 사용하여 사용자 조회 (포인트 차감 시 동시성 제어)
        // - userId는 UNIQUE 인덱스가 있어 Lock 범위 최소화 (Record Lock만 적용)
        // - Lost Update 방지: 동시 주문 시 포인트 중복 차감 방지 (금전적 손실 방지)
        // - 트랜잭션 내부에 외부 I/O 없음, lock holding time 매우 짧음
        User user = userService.findByUserIdForUpdate(userId);

        // ✅ Deadlock 방지: 상품 ID를 정렬하여 일관된 락 획득 순서 보장
        // 여러 상품을 주문할 때, 항상 동일한 순서로 락을 획득하여 deadlock 방지
        List<Long> sortedProductIds = commands.stream()
            .map(OrderItemCommand::productId)
            .distinct()
            .sorted()
            .toList();

        // 중복 상품 검증
        if (sortedProductIds.size() != commands.size()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품이 중복되었습니다.");
        }

        // 정렬된 순서대로 상품 락 획득 (Deadlock 방지)
        Map<Long, Product> productMap = new java.util.HashMap<>();

        for (Long productId : sortedProductIds) {
            // 비관적 락을 사용하여 상품 조회 (재고 차감 시 동시성 제어)
            // - id는 PK 인덱스가 있어 Lock 범위 최소화 (Record Lock만 적용)
            // - Lost Update 방지: 동시 주문 시 재고 음수 방지 및 정확한 차감 보장 (재고 oversell 방지)
            // - 트랜잭션 내부에 외부 I/O 없음, lock holding time 매우 짧음
            // - ✅ 정렬된 순서로 락 획득하여 deadlock 방지
            Product product = productService.findByIdForUpdate(productId);
            productMap.put(productId, product);
        }

        // OrderItem 생성
        List<Product> products = new ArrayList<>();
        List<OrderItem> orderItems = new ArrayList<>();
        for (OrderItemCommand command : commands) {
            Product product = productMap.get(command.productId());
            products.add(product);

            orderItems.add(OrderItem.of(
                product.getId(),
                product.getName(),
                product.getPrice(),
                command.quantity()
            ));
        }

        // 쿠폰 처리 (있는 경우)
        String couponCode = extractCouponCode(commands);
        Integer discountAmount = 0;
        if (couponCode != null && !couponCode.isBlank()) {
            discountAmount = couponService.applyCoupon(user.getId(), couponCode, calculateSubtotal(orderItems));
        }

        // 포인트 차감 (지정된 금액만)
        Long usedPointAmount = Objects.requireNonNullElse(usedPoint, 0L);
        
        // 포인트 잔액 검증: 포인트를 사용하는 경우에만 검증
        // 재고 차감 전에 검증하여 원자성 보장 (검증 실패 시 아무것도 변경되지 않음)
        if (usedPointAmount > 0) {
            Long userPointBalance = user.getPoint().getValue();
            if (userPointBalance < usedPointAmount) {
                throw new CoreException(ErrorType.BAD_REQUEST,
                    String.format("포인트가 부족합니다. (현재 잔액: %d, 사용 요청 금액: %d)", userPointBalance, usedPointAmount));
            }
        }

        // OrderService를 사용하여 주문 생성
        Order savedOrder = orderService.create(user.getId(), orderItems, couponCode, discountAmount);
        // 주문은 PENDING 상태로 생성됨 (Order 생성자에서 기본값으로 설정)
        // 결제 성공 후에만 COMPLETED로 변경됨

        // 재고 차감
        decreaseStocksForOrderItems(savedOrder.getItems(), products);

        // 포인트 차감 (지정된 금액만)
        if (usedPointAmount > 0) {
            deductUserPoint(user, usedPointAmount.intValue());
        }

        // PG 결제 금액 계산
        // Order.getTotalAmount()는 이미 쿠폰 할인이 적용된 금액이므로 discountAmount를 다시 빼면 안 됨
        Long totalAmount = savedOrder.getTotalAmount().longValue();
        Long paidAmount = totalAmount - usedPointAmount;

        // Payment 생성 (포인트+쿠폰 혼합 지원)
        CardType cardTypeEnum = (cardType != null && !cardType.isBlank()) ? convertCardType(cardType) : null;
        Payment payment = paymentService.create(
            savedOrder.getId(),
            user.getId(),
            totalAmount,
            usedPointAmount,
            cardTypeEnum,
            cardNo,
            java.time.LocalDateTime.now()
        );

        // 포인트+쿠폰으로 전액 결제 완료된 경우
        if (paidAmount == 0) {
            // PG 요청 없이 바로 완료
            orderService.completeOrder(savedOrder.getId());
            paymentService.toSuccess(payment.getId(), java.time.LocalDateTime.now());
            productService.saveAll(products);
            userService.save(user);
            log.info("포인트+쿠폰으로 전액 결제 완료. (orderId: {})", savedOrder.getId());
            return OrderInfo.from(savedOrder);
        }

        // PG 결제가 필요한 경우
        if (cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                "포인트와 쿠폰만으로 결제할 수 없습니다. 카드 정보를 입력해주세요.");
        }

        productService.saveAll(products);
        userService.save(user);

        // PG 결제 요청을 트랜잭션 커밋 후에 실행하여 DB 커넥션 풀 고갈 방지
        // 트랜잭션 내에서 외부 HTTP 호출을 하면 PG 지연/타임아웃 시 DB 커넥션이 오래 유지되어 커넥션 풀 고갈 위험
        Long orderId = savedOrder.getId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 트랜잭션 커밋 후 PG 호출 (DB 커넥션 해제 후 실행)
                    try {
                        String transactionKey = requestPaymentToGateway(
                            userId, user.getId(), orderId, cardType, cardNo, paidAmount.intValue()
                        );
                        if (transactionKey != null) {
                            // 결제 성공: 별도 트랜잭션에서 주문 상태를 COMPLETED로 변경
                            updateOrderStatusToCompleted(orderId, transactionKey);
                            log.info("PG 결제 요청 완료. (orderId: {}, transactionKey: {})", orderId, transactionKey);
                        } else {
                            // PG 요청 실패: 외부 시스템 장애로 간주
                            // 주문은 PENDING 상태로 유지되어 나중에 상태 확인 API나 콜백으로 복구 가능
                            log.info("PG 결제 요청 실패. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
                        }
                    } catch (Exception e) {
                        // PG 요청 중 예외 발생 시에도 주문은 이미 저장되어 있으므로 유지
                        // 외부 시스템 장애는 내부 시스템에 영향을 주지 않도록 함
                        log.error("PG 결제 요청 중 예외 발생. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", 
                            orderId, e);
                    }
                }
            }
        );

        return OrderInfo.from(savedOrder);
    }

    /**
     * 주문을 취소하고 포인트를 환불하며 재고를 원복한다.
     * <p>
     * <b>동시성 제어:</b>
     * <ul>
     *   <li><b>비관적 락 사용:</b> 재고 원복 시 동시성 제어를 위해 findByIdForUpdate 사용</li>
     *   <li><b>Deadlock 방지:</b> 상품 ID를 정렬하여 일관된 락 획득 순서 보장</li>
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

        // ✅ Deadlock 방지: User 락을 먼저 획득하여 createOrder와 동일한 락 획득 순서 보장
        User lockedUser = userService.findByUserIdForUpdate(user.getUserId());
        if (lockedUser == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }

        // ✅ Deadlock 방지: 상품 ID를 정렬하여 일관된 락 획득 순서 보장
        List<Long> sortedProductIds = order.getItems().stream()
            .map(OrderItem::getProductId)
            .distinct()
            .sorted()
            .toList();

        // 정렬된 순서대로 상품 락 획득 (Deadlock 방지)
        Map<Long, Product> productMap = new java.util.HashMap<>();
        for (Long productId : sortedProductIds) {
            Product product = productService.findByIdForUpdate(productId);
            productMap.put(productId, product);
        }

        // OrderItem 순서대로 Product 리스트 생성
        List<Product> products = order.getItems().stream()
            .map(item -> productMap.get(item.getProductId()))
            .toList();

        // 실제로 사용된 포인트만 환불 (Payment에서 확인)
        Long refundPointAmount = paymentService.findByOrderId(order.getId())
            .map(Payment::getUsedPoint)
            .orElse(0L);

        // 도메인 서비스를 통한 주문 취소 처리
        orderService.cancelOrder(order, products, lockedUser, refundPointAmount);

        // 저장
        productService.saveAll(products);
        userService.save(lockedUser);
    }

    /**
     * 사용자 ID로 주문 목록을 조회한다.
     *
     * @param userId 사용자 식별자 (로그인 ID)
     * @return 주문 목록
     */
    @Transactional(readOnly = true)
    public List<OrderInfo> getOrders(String userId) {
        User user = userService.findByUserId(userId);
        List<Order> orders = orderService.findAllByUserId(user.getId());
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
        User user = userService.findByUserId(userId);
        Order order = orderService.getById(orderId);

        if (!order.getUserId().equals(user.getId())) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }

        return OrderInfo.from(order);
    }

    private void decreaseStocksForOrderItems(List<OrderItem> items, List<Product> products) {
        Map<Long, Product> productMap = products.stream()
            .collect(Collectors.toMap(Product::getId, product -> product));

        for (OrderItem item : items) {
            Product product = productMap.get(item.getProductId());
            if (product == null) {
                throw new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", item.getProductId()));
            }
            product.decreaseStock(item.getQuantity());
        }
    }


    private void deductUserPoint(User user, Integer totalAmount) {
        if (Objects.requireNonNullElse(totalAmount, 0) <= 0) {
            return;
        }
        user.deductPoint(Point.of(totalAmount.longValue()));
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
     * 카드 타입 문자열을 CardType enum으로 변환합니다.
     *
     * @param cardType 카드 타입 문자열
     * @return CardType enum
     * @throws CoreException 잘못된 카드 타입인 경우
     */
    private CardType convertCardType(String cardType) {
        try {
            return CardType.valueOf(cardType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                String.format("잘못된 카드 타입입니다. (cardType: %s)", cardType));
        }
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
            Order order = orderService.findById(orderId).orElse(null);
            
            if (order == null) {
                log.warn("주문 상태 업데이트 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
                return false;
            }
            
            // 이미 완료되거나 취소된 주문인 경우 처리하지 않음 (정상적인 경우이므로 true 반환)
            if (order.getStatus() == OrderStatus.COMPLETED) {
                log.info("이미 완료된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return true;
            }
            
            if (order.getStatus() == OrderStatus.CANCELED) {
                log.info("이미 취소된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
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
                User user = userService.findById(order.getUserId());
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
                log.info("결제 상태 확인 결과, 아직 처리 중입니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
                return true;
            }
        } catch (Exception e) {
            log.error("주문 상태 업데이트 중 오류 발생. (orderId: {})", orderId, e);
            return false;
        }
    }

    /**
     * 주문 상태를 COMPLETED로 업데이트합니다.
     * <p>
     * 트랜잭션 커밋 후 별도 트랜잭션에서 실행되어 주문 상태를 업데이트합니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param transactionKey 트랜잭션 키
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void updateOrderStatusToCompleted(Long orderId, String transactionKey) {
        try {
            Order order = orderService.getById(orderId);
            
            if (order.getStatus() == OrderStatus.COMPLETED) {
                log.info("이미 완료된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return;
            }
            
            // Payment 상태 업데이트 (PaymentService 사용)
            paymentService.findByOrderId(orderId).ifPresent(payment -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    paymentService.toSuccess(payment.getId(), java.time.LocalDateTime.now());
                }
            });
            
            orderService.completeOrder(orderId);
            log.info("주문 상태를 COMPLETED로 업데이트했습니다. (orderId: {}, transactionKey: {})", orderId, transactionKey);
        } catch (CoreException e) {
            log.warn("주문 상태 업데이트 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
        }
    }
    
    /**
     * PG 결제 게이트웨이에 결제 요청을 전송합니다.
     * <p>
     * 트랜잭션 커밋 후 실행되어 DB 커넥션 풀 고갈을 방지합니다.
     * 실패 시에도 주문은 이미 저장되어 있으므로, 로그만 기록합니다.
     * </p>
     *
     * @param userId 사용자 ID (String - User.userId, PG 요청용)
     * @param userEntityId 사용자 엔티티 ID (Long - User.id, Payment 엔티티용)
     * @param orderId 주문 ID
     * @param cardType 카드 타입
     * @param cardNo 카드 번호
     * @param amount 결제 금액
     * @return transactionKey (성공 시), null (실패 시)
     */
    private String requestPaymentToGateway(String userId, Long userEntityId, Long orderId, String cardType, String cardNo, Integer amount) {
        try {
            // PaymentService를 통한 PG 결제 요청
            PaymentRequestResult result = paymentService.requestPayment(
                orderId, userId, userEntityId, cardType, cardNo, amount.longValue()
            );
            
            // 결과 처리
            if (result instanceof PaymentRequestResult.Success success) {
                log.info("PG 결제 요청 성공. (orderId: {}, transactionKey: {})",
                    orderId, success.transactionKey());
                return success.transactionKey();
            } else if (result instanceof PaymentRequestResult.Failure failure) {
                // PaymentService 내부에서 이미 실패 분류가 완료되었으므로, 여기서는 처리만 수행
                // 비즈니스 실패는 PaymentService에서 이미 처리되었으므로, 여기서는 타임아웃/외부 시스템 장애만 처리
                
                // Circuit Breaker OPEN은 외부 시스템 장애이므로 주문을 취소하지 않음
                if ("CIRCUIT_BREAKER_OPEN".equals(failure.errorCode())) {
                    // 외부 시스템 장애: 주문은 PENDING 상태로 유지
                    log.info("Circuit Breaker OPEN 상태. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
                    return null;
                }
                
                if (failure.isTimeout()) {
                    // 타임아웃: 상태 확인 후 복구
                    log.info("타임아웃 발생. PG 결제 상태 확인 API를 호출하여 실제 결제 상태를 확인합니다. (orderId: {})", orderId);
                    paymentService.recoverAfterTimeout(userId, orderId);
                } else if (!failure.isRetryable()) {
                    // 비즈니스 실패: 주문 취소 (별도 트랜잭션으로 처리)
                    handlePaymentFailure(userId, orderId, failure.errorCode(), failure.message());
                }
                // 외부 시스템 장애는 PaymentService에서 이미 PENDING 상태로 유지하므로 추가 처리 불필요
                return null;
            }
            
            return null;
        } catch (CoreException e) {
            // 잘못된 카드 타입 등 검증 오류
            log.warn("결제 요청 실패. (orderId: {}, error: {})", orderId, e.getMessage());
            return null;
        } catch (Exception e) {
            // 기타 예외 처리
            log.error("PG 결제 요청 중 예상치 못한 오류 발생. (orderId: {})", orderId, e);
            log.info("예상치 못한 오류 발생. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
            return null;
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
                user = userService.findById(userId);
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

    /**
     * 결제 실패 시 주문 취소 및 리소스 원복을 처리합니다.
     * <p>
     * 결제 요청이 실패한 경우, 이미 생성된 주문을 취소하고
     * 차감된 포인트를 환불하며 재고를 원복합니다.
     * </p>
     * <p>
     * <b>처리 내용:</b>
     * <ul>
     *   <li>주문 상태를 CANCELED로 변경</li>
     *   <li>차감된 포인트 환불</li>
     *   <li>차감된 재고 원복</li>
     * </ul>
     * </p>
     * <p>
     * <b>트랜잭션 전략:</b>
     * <ul>
     *   <li>TransactionTemplate 사용: afterCommit 콜백에서 호출되므로 명시적으로 새 트랜잭션 생성</li>
     *   <li>결제 실패 처리 중 오류가 발생해도 기존 주문 생성 트랜잭션에 영향을 주지 않음</li>
     *   <li>Self-invocation 문제 해결: TransactionTemplate을 사용하여 명시적으로 트랜잭션 관리</li>
     * </ul>
     * </p>
     * <p>
     * <b>주의사항:</b>
     * <ul>
     *   <li>주문이 이미 취소되었거나 존재하지 않는 경우 로그만 기록합니다.</li>
     *   <li>결제 실패 처리 중 오류 발생 시에도 로그만 기록합니다.</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID (로그인 ID)
     * @param orderId 주문 ID
     * @param errorCode 오류 코드
     * @param errorMessage 오류 메시지
     */
    private void handlePaymentFailure(String userId, Long orderId, String errorCode, String errorMessage) {
        // TransactionTemplate을 사용하여 명시적으로 새 트랜잭션 생성
        // afterCommit 콜백에서 호출되므로 @Transactional 어노테이션이 작동하지 않음
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        
        transactionTemplate.executeWithoutResult(status -> {
            try {
                // 사용자 조회 (Service를 통한 접근)
                User user;
                try {
                    user = userService.findByUserId(userId);
                } catch (CoreException e) {
                    log.warn("결제 실패 처리 시 사용자를 찾을 수 없습니다. (userId: {}, orderId: {})", userId, orderId);
                    return;
                }

                // 주문 조회 (Service를 통한 접근)
                Order order;
                try {
                    order = orderService.getById(orderId);
                } catch (CoreException e) {
                    log.warn("결제 실패 처리 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
                    return;
                }

                // 이미 취소된 주문인 경우 처리하지 않음
                if (order.getStatus() == OrderStatus.CANCELED) {
                    log.info("이미 취소된 주문입니다. 결제 실패 처리를 건너뜁니다. (orderId: {})", orderId);
                    return;
                }

                // 주문 취소 및 리소스 원복
                cancelOrder(order, user);

                log.info("결제 실패로 인한 주문 취소 완료. (orderId: {}, errorCode: {}, errorMessage: {})",
                    orderId, errorCode, errorMessage);
            } catch (Exception e) {
                // 결제 실패 처리 중 오류 발생 시에도 로그만 기록
                // 이미 주문은 생성되어 있으므로, 나중에 수동으로 처리할 수 있도록 로그 기록
                log.error("결제 실패 처리 중 오류 발생. (orderId: {}, errorCode: {})",
                    orderId, errorCode, e);
                // 예외를 다시 던져서 트랜잭션이 롤백되도록 함
                throw new RuntimeException("결제 실패 처리 중 오류 발생", e);
            }
        });
    }

}

