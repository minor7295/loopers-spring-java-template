package com.loopers.application.product;

import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.product.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 상품 이벤트 핸들러.
 * <p>
 * 좋아요 추가/취소 이벤트와 주문 생성/취소 이벤트를 받아 상품의 좋아요 수 및 재고를 업데이트하는 애플리케이션 로직을 처리합니다.
 * </p>
 * <p>
 * <b>DDD/EDA 관점:</b>
 * <ul>
 *   <li><b>책임 분리:</b> ProductService는 상품 도메인 비즈니스 로직, ProductEventHandler는 이벤트 처리 로직</li>
 *   <li><b>이벤트 핸들러:</b> 이벤트를 받아서 처리하는 역할을 명확히 나타냄</li>
 *   <li><b>도메인 경계 준수:</b> 상품 도메인은 자신의 상태만 관리하며, 주문 생성/취소 이벤트를 구독하여 재고 관리</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventHandler {

    private final ProductService productService;

    /**
     * 좋아요 추가 이벤트를 처리하여 상품의 좋아요 수를 증가시킵니다.
     *
     * @param event 좋아요 추가 이벤트
     */
    @Transactional
    public void handleLikeAdded(LikeEvent.LikeAdded event) {
        log.debug("좋아요 추가 이벤트 처리: productId={}, userId={}", 
            event.productId(), event.userId());
        
        // ✅ 이벤트 기반 실시간 집계: Product.likeCount 직접 증가
        productService.incrementLikeCount(event.productId());
        
        log.debug("좋아요 수 증가 완료: productId={}", event.productId());
    }

    /**
     * 좋아요 취소 이벤트를 처리하여 상품의 좋아요 수를 감소시킵니다.
     *
     * @param event 좋아요 취소 이벤트
     */
    @Transactional
    public void handleLikeRemoved(LikeEvent.LikeRemoved event) {
        log.debug("좋아요 취소 이벤트 처리: productId={}, userId={}", 
            event.productId(), event.userId());
        
        // ✅ 이벤트 기반 실시간 집계: Product.likeCount 직접 감소
        productService.decrementLikeCount(event.productId());
        
        log.debug("좋아요 수 감소 완료: productId={}", event.productId());
    }

    /**
     * 주문 생성 이벤트를 처리하여 재고를 차감합니다.
     * <p>
     * <b>동시성 제어:</b>
     * <ul>
     *   <li><b>비관적 락 사용:</b> 재고 차감 시 동시성 제어를 위해 findByIdForUpdate 사용</li>
     *   <li><b>Deadlock 방지:</b> 상품 ID를 정렬하여 일관된 락 획득 순서 보장</li>
     * </ul>
     * </p>
     *
     * @param event 주문 생성 이벤트
     */
    @Transactional
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        if (event.orderItems() == null || event.orderItems().isEmpty()) {
            log.debug("주문 아이템이 없어 재고 차감을 건너뜁니다. (orderId: {})", event.orderId());
            return;
        }

        try {
            // ✅ Deadlock 방지: 상품 ID를 정렬하여 일관된 락 획득 순서 보장
            List<Long> sortedProductIds = event.orderItems().stream()
                    .map(OrderEvent.OrderCreated.OrderItemInfo::productId)
                    .distinct()
                    .sorted()
                    .toList();

            // 정렬된 순서대로 상품 락 획득 (Deadlock 방지)
            Map<Long, Product> productMap = new HashMap<>();
            for (Long productId : sortedProductIds) {
                Product product = productService.getProductForUpdate(productId);
                productMap.put(productId, product);
            }

            // 재고 차감
            for (OrderEvent.OrderCreated.OrderItemInfo itemInfo : event.orderItems()) {
                Product product = productMap.get(itemInfo.productId());
                if (product == null) {
                    log.warn("상품을 찾을 수 없습니다. (orderId: {}, productId: {})",
                            event.orderId(), itemInfo.productId());
                    continue;
                }
                product.decreaseStock(itemInfo.quantity());
            }

            // 저장
            productService.saveAll(productMap.values().stream().toList());

            log.info("주문 생성으로 인한 재고 차감 완료. (orderId: {})", event.orderId());
        } catch (Exception e) {
            log.error("주문 생성 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            throw e;
        }
    }

    /**
     * 주문 취소 이벤트를 처리하여 재고를 원복합니다.
     * <p>
     * <b>동시성 제어:</b>
     * <ul>
     *   <li><b>비관적 락 사용:</b> 재고 원복 시 동시성 제어를 위해 findByIdForUpdate 사용</li>
     *   <li><b>Deadlock 방지:</b> 상품 ID를 정렬하여 일관된 락 획득 순서 보장</li>
     * </ul>
     * </p>
     *
     * @param event 주문 취소 이벤트
     */
    @Transactional
    public void handleOrderCanceled(OrderEvent.OrderCanceled event) {
        if (event.orderItems() == null || event.orderItems().isEmpty()) {
            log.debug("주문 아이템이 없어 재고 원복을 건너뜁니다. (orderId: {})", event.orderId());
            return;
        }

        try {
            // ✅ Deadlock 방지: 상품 ID를 정렬하여 일관된 락 획득 순서 보장
            List<Long> sortedProductIds = event.orderItems().stream()
                    .map(OrderEvent.OrderCanceled.OrderItemInfo::productId)
                    .distinct()
                    .sorted()
                    .toList();

            // 정렬된 순서대로 상품 락 획득 (Deadlock 방지)
            Map<Long, Product> productMap = new HashMap<>();
            for (Long productId : sortedProductIds) {
                Product product = productService.getProductForUpdate(productId);
                productMap.put(productId, product);
            }

            // 재고 원복
            for (OrderEvent.OrderCanceled.OrderItemInfo itemInfo : event.orderItems()) {
                Product product = productMap.get(itemInfo.productId());
                if (product == null) {
                    log.warn("상품을 찾을 수 없습니다. (orderId: {}, productId: {})",
                            event.orderId(), itemInfo.productId());
                    continue;
                }
                product.increaseStock(itemInfo.quantity());
            }

            // 저장
            productService.saveAll(productMap.values().stream().toList());

            log.info("주문 취소로 인한 재고 원복 완료. (orderId: {})", event.orderId());
        } catch (Exception e) {
            log.error("주문 취소 이벤트 처리 중 오류 발생. (orderId: {})", event.orderId(), e);
            throw e;
        }
    }
}

