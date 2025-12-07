package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 주문 도메인 서비스.
 * <p>
 * 주문의 기본 CRUD 및 상태 변경을 담당합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

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
    public Optional<Order> findById(Long orderId) {
        return orderRepository.findById(orderId);
    }

    /**
     * 사용자 ID로 주문 목록을 조회합니다.
     *
     * @param userId 사용자 ID
     * @return 해당 사용자의 주문 목록
     */
    @Transactional(readOnly = true)
    public List<Order> findAllByUserId(Long userId) {
        return orderRepository.findAllByUserId(userId);
    }

    /**
     * 주문 상태로 주문 목록을 조회합니다.
     *
     * @param status 주문 상태
     * @return 해당 상태의 주문 목록
     */
    @Transactional(readOnly = true)
    public List<Order> findAllByStatus(OrderStatus status) {
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
        return orderRepository.save(order);
    }

    /**
     * 주문을 완료 상태로 변경합니다.
     *
     * @param orderId 주문 ID
     * @return 완료된 주문
     * @throws CoreException 주문을 찾을 수 없는 경우
     */
    @Transactional
    public Order completeOrder(Long orderId) {
        Order order = getById(orderId);
        order.complete();
        return orderRepository.save(order);
    }
}

