package com.loopers.application.product;

import com.loopers.domain.like.LikeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 이벤트 핸들러.
 * <p>
 * 좋아요 추가/취소 이벤트를 받아 상품의 좋아요 수를 업데이트하는 애플리케이션 로직을 처리합니다.
 * </p>
 * <p>
 * <b>DDD/EDA 관점:</b>
 * <ul>
 *   <li><b>책임 분리:</b> ProductService는 상품 도메인 비즈니스 로직, ProductEventHandler는 이벤트 처리 로직</li>
 *   <li><b>이벤트 핸들러:</b> 이벤트를 받아서 처리하는 역할을 명확히 나타냄</li>
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
}

