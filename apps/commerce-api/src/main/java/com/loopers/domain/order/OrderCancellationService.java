package com.loopers.domain.order;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 취소 도메인 서비스.
 * <p>
 * 주문 취소 및 리소스 원복을 처리합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancellationService {
    
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final PaymentService paymentService;
    
    /**
     * 주문을 취소하고 포인트를 환불하며 재고를 원복합니다.
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
    public void cancel(Order order, User user) {
        if (order == null || user == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "취소할 주문과 사용자 정보는 필수입니다.");
        }
        
        // ✅ Deadlock 방지: User 락을 먼저 획득하여 createOrder와 동일한 락 획득 순서 보장
        User lockedUser = userRepository.findByUserIdForUpdate(user.getUserId());
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
        Map<Long, Product> productMap = new HashMap<>();
        for (Long productId : sortedProductIds) {
            Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId)));
            productMap.put(productId, product);
        }
        
        // OrderItem 순서대로 Product 리스트 생성
        List<Product> products = order.getItems().stream()
            .map(item -> productMap.get(item.getProductId()))
            .toList();
        
        order.cancel();
        increaseStocksForOrderItems(order.getItems(), products);
        
        // 실제로 사용된 포인트만 환불 (Payment에서 확인)
        Long refundPointAmount = paymentService.findByOrderId(order.getId())
            .map(Payment::getUsedPoint)
            .orElse(0L);
        
        if (refundPointAmount > 0) {
            lockedUser.receivePoint(Point.of(refundPointAmount));
        }
        
        products.forEach(productRepository::save);
        userRepository.save(lockedUser);
        orderRepository.save(order);
    }
    
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

