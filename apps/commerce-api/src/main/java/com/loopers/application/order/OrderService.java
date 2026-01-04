package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 주문 애플리케이션 서비스.
 * <p>
 * 주문의 기본 CRUD 및 상태 변경을 담당하는 애플리케이션 서비스입니다.
 * Repository에 의존하며 트랜잭션 관리를 담당합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * 주문을 저장합니다.
     *
     * @param order 저장할 주문
     * @return 저장된 주문
     */
    @Transactional
    public Order save(Order order) {
        return orderRepository.save(order);
    }

    /**
     * 주문 ID로 주문을 조회합니다.
     *
     * @param orderId 주문 ID
     * @return 조회된 주문
     * @throws CoreException 주문을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public Order getById(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    /**
     * 주문 ID로 주문을 조회합니다 (Optional 반환).
     *
     * @param orderId 주문 ID
     * @return 조회된 주문 (없으면 Optional.empty())
     */
    @Transactional(readOnly = true)
    public Optional<Order> getOrder(Long orderId) {
        return orderRepository.findById(orderId);
    }

    /**
     * 사용자 ID로 주문 목록을 조회합니다.
     *
     * @param userId 사용자 ID
     * @return 해당 사용자의 주문 목록
     */
    @Transactional(readOnly = true)
    public List<Order> getOrdersByUserId(Long userId) {
        return orderRepository.findAllByUserId(userId);
    }

    /**
     * 주문 상태로 주문 목록을 조회합니다.
     *
     * @param status 주문 상태
     * @return 해당 상태의 주문 목록
     */
    @Transactional(readOnly = true)
    public List<Order> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findAllByStatus(status);
    }

    /**
     * 주문을 생성합니다.
     *
     * @param userId 사용자 ID
     * @param items 주문 아이템 목록
     * @param couponCode 쿠폰 코드 (선택)
     * @param discountAmount 할인 금액 (선택)
     * @return 생성된 주문
     */
    @Transactional
    public Order create(Long userId, List<OrderItem> items, String couponCode, Integer discountAmount) {
        Order order = Order.of(userId, items, couponCode, discountAmount);
        return orderRepository.save(order);
    }

    /**
     * 주문을 생성합니다 (쿠폰 없음).
     *
     * @param userId 사용자 ID
     * @param items 주문 아이템 목록
     * @return 생성된 주문
     */
    @Transactional
    public Order create(Long userId, List<OrderItem> items) {
        Order order = Order.of(userId, items);
        Order savedOrder = orderRepository.save(order);
        
        // 소계 계산
        Integer subtotal = calculateSubtotal(items);
        
        // ✅ 도메인 이벤트 발행: 주문이 생성되었음 (과거 사실)
        orderEventPublisher.publish(OrderEvent.OrderCreated.from(savedOrder, subtotal, 0L));
        
        return savedOrder;
    }

    /**
     * 주문을 생성합니다 (쿠폰 코드와 소계 포함).
     * <p>
     * 주문 생성 후 OrderCreated 이벤트를 발행합니다.
     * </p>
     *
     * @param command 주문 생성 명령
     * @return 생성된 주문
     */
    @Transactional
    public Order create(CreateOrderCommand command) {
        // 쿠폰이 있어도 discountAmount는 0으로 설정 (CouponEventHandler가 이벤트를 받아 쿠폰 적용)
        Order order = Order.of(command.userId(), command.items(), command.couponCode(), 0);
        Order savedOrder = orderRepository.save(order);
        
        // ✅ 도메인 이벤트 발행: 주문이 생성되었음 (과거 사실)
        orderEventPublisher.publish(OrderEvent.OrderCreated.from(savedOrder, command.subtotal(), command.usedPointAmount()));
        
        return savedOrder;
    }

    /**
     * 주문에 쿠폰 할인 금액을 적용합니다.
     * <p>
     * 이벤트 핸들러에서 쿠폰 적용 후 호출됩니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param discountAmount 할인 금액
     * @return 업데이트된 주문
     * @throws CoreException 주문을 찾을 수 없거나 할인을 적용할 수 없는 상태인 경우
     */
    @Transactional
    public Order applyCouponDiscount(Long orderId, Integer discountAmount) {
        Order order = getById(orderId);
        order.applyDiscount(discountAmount);
        return orderRepository.save(order);
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
     * 주문을 완료 상태로 변경합니다.
     * <p>
     * 주문 완료 후 OrderCompleted 이벤트를 발행합니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @return 완료된 주문
     * @throws CoreException 주문을 찾을 수 없는 경우
     */
    @Transactional
    public Order completeOrder(Long orderId) {
        Order order = getById(orderId);
        order.complete();
        Order savedOrder = orderRepository.save(order);
        
        // ✅ 도메인 이벤트 발행: 주문이 완료되었음 (과거 사실)
        orderEventPublisher.publish(OrderEvent.OrderCompleted.from(savedOrder));
        
        return savedOrder;
    }

    /**
     * 주문을 취소 상태로 변경합니다.
     * <p>
     * 주문 취소 후 OrderCanceled 이벤트를 발행합니다.
     * 리소스 원복(재고, 포인트)은 별도 이벤트 핸들러에서 처리합니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param reason 취소 사유
     * @param refundPointAmount 환불할 포인트 금액
     * @return 취소된 주문
     * @throws CoreException 주문을 찾을 수 없는 경우
     */
    @Transactional
    public Order cancelOrder(Long orderId, String reason, Long refundPointAmount) {
        Order order = getById(orderId);
        order.cancel();
        Order savedOrder = orderRepository.save(order);
        
        // ✅ 도메인 이벤트 발행: 주문이 취소되었음 (과거 사실)
        orderEventPublisher.publish(OrderEvent.OrderCanceled.from(savedOrder, reason, refundPointAmount));
        
        return savedOrder;
    }

    /**
     * 주문을 취소 상태로 변경하고 재고를 원복하며 포인트를 환불합니다.
     * <p>
     * 도메인 로직만 처리합니다. 사용자 조회, 상품 조회, Payment 조회는 애플리케이션 레이어에서 처리합니다.
     * </p>
     *
     * @param order 주문 엔티티
     * @param products 주문 아이템에 해당하는 상품 목록 (락이 이미 획득된 상태)
     * @param user 사용자 엔티티 (락이 이미 획득된 상태)
     * @param refundPointAmount 환불할 포인트 금액
     * @throws CoreException 주문 또는 사용자 정보가 null인 경우
     */
    @Transactional
    public void cancelOrder(Order order, List<Product> products, User user, Long refundPointAmount) {
        if (order == null || user == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "취소할 주문과 사용자 정보는 필수입니다.");
        }

        // 주문 취소
        order.cancel();

        // 재고 원복
        increaseStocksForOrderItems(order.getItems(), products);

        // 포인트 환불
        if (refundPointAmount > 0) {
            user.receivePoint(Point.of(refundPointAmount));
        }

        orderRepository.save(order);
    }

    /**
     * 결제 상태에 따라 주문 상태를 업데이트합니다.
     * <p>
     * 주문 상태 변경 후 해당 이벤트(OrderCompleted 또는 OrderCanceled)를 발행합니다.
     * </p>
     * <p>
     * 도메인 로직만 처리합니다. 사용자 조회, 트랜잭션 관리, 로깅은 애플리케이션 레이어에서 처리합니다.
     * </p>
     *
     * @param order 주문 엔티티
     * @param paymentStatus 결제 상태
     * @param reason 취소 사유 (FAILED인 경우 필수, 그 외 null 가능)
     * @param refundPointAmount 환불할 포인트 금액 (FAILED인 경우 필수, 그 외 null 가능)
     * @throws CoreException 주문이 null이거나 이미 완료/취소된 경우
     */
    @Transactional
    public void updateStatusByPaymentResult(Order order, PaymentStatus paymentStatus, String reason, Long refundPointAmount) {
        if (order == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 정보는 필수입니다.");
        }

        // 이미 완료되거나 취소된 주문인 경우 처리하지 않음
        if (order.isCompleted() || order.isCanceled()) {
            return;
        }

        if (paymentStatus == PaymentStatus.SUCCESS) {
            // 결제 성공: 주문 완료
            order.complete();
            Order savedOrder = orderRepository.save(order);
            
            // ✅ 도메인 이벤트 발행: 주문이 완료되었음 (과거 사실)
            orderEventPublisher.publish(OrderEvent.OrderCompleted.from(savedOrder));
        } else if (paymentStatus == PaymentStatus.FAILED) {
            // 결제 실패: 주문 취소 (재고 원복 및 포인트 환불은 이벤트 핸들러에서 처리)
            if (reason == null || reason.isBlank()) {
                throw new CoreException(ErrorType.BAD_REQUEST, "결제 실패 시 취소 사유는 필수입니다.");
            }
            if (refundPointAmount == null || refundPointAmount < 0) {
                throw new CoreException(ErrorType.BAD_REQUEST, "환불할 포인트 금액은 0 이상이어야 합니다.");
            }
            
            order.cancel();
            Order savedOrder = orderRepository.save(order);
            
            // ✅ 도메인 이벤트 발행: 주문이 취소되었음 (과거 사실)
            orderEventPublisher.publish(OrderEvent.OrderCanceled.from(savedOrder, reason, refundPointAmount));
        }
        // PENDING 상태: 상태 유지 (아무 작업도 하지 않음)
    }

    /**
     * 주문 아이템에 대해 재고를 증가시킵니다.
     *
     * @param items 주문 아이템 목록
     * @param products 상품 목록
     */
    private void increaseStocksForOrderItems(List<OrderItem> items, List<Product> products) {
        Map<Long, Product> productMap = products.stream()
            .collect(java.util.stream.Collectors.toMap(Product::getId, product -> product));

        for (OrderItem item : items) {
            Product product = productMap.get(item.getProductId());
            if (product == null) {
                throw new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", item.getProductId()));
            }
            product.increaseStock(item.getQuantity());
        }
    }
}

