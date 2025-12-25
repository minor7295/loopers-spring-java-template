package com.loopers.application.ranking;

import com.loopers.domain.event.LikeEvent;
import com.loopers.domain.event.OrderEvent;
import com.loopers.domain.event.ProductEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 랭킹 이벤트 핸들러.
 * <p>
 * 좋아요 추가/취소, 주문 생성, 상품 조회 이벤트를 받아 랭킹 점수를 집계하는 애플리케이션 로직을 처리합니다.
 * </p>
 * <p>
 * <b>DDD/EDA 관점:</b>
 * <ul>
 *   <li><b>책임 분리:</b> RankingService는 랭킹 점수 계산/적재, RankingEventHandler는 이벤트 처리 로직</li>
 *   <li><b>이벤트 핸들러:</b> 이벤트를 받아서 처리하는 역할을 명확히 나타냄</li>
 *   <li><b>도메인 경계 준수:</b> 랭킹은 파생 View로 취급하며, 도메인 이벤트를 구독하여 집계</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingEventHandler {

    private final RankingService rankingService;

    /**
     * 좋아요 추가 이벤트를 처리하여 랭킹 점수를 추가합니다.
     *
     * @param event 좋아요 추가 이벤트
     */
    public void handleLikeAdded(LikeEvent.LikeAdded event) {
        log.debug("좋아요 추가 이벤트 처리: productId={}, userId={}", 
            event.productId(), event.userId());
        
        LocalDate date = LocalDate.now();
        rankingService.addLikeScore(event.productId(), date, true);
        
        log.debug("좋아요 점수 추가 완료: productId={}", event.productId());
    }

    /**
     * 좋아요 취소 이벤트를 처리하여 랭킹 점수를 차감합니다.
     *
     * @param event 좋아요 취소 이벤트
     */
    public void handleLikeRemoved(LikeEvent.LikeRemoved event) {
        log.debug("좋아요 취소 이벤트 처리: productId={}, userId={}", 
            event.productId(), event.userId());
        
        LocalDate date = LocalDate.now();
        rankingService.addLikeScore(event.productId(), date, false);
        
        log.debug("좋아요 점수 차감 완료: productId={}", event.productId());
    }

    /**
     * 주문 생성 이벤트를 처리하여 랭킹 점수를 추가합니다.
     * <p>
     * <b>주문 금액 계산:</b>
     * <ul>
     *   <li>OrderEvent.OrderCreated에는 개별 상품 가격 정보가 없음</li>
     *   <li>subtotal을 totalQuantity로 나눠서 평균 단가를 구하고, 각 아이템의 quantity를 곱함</li>
     *   <li>향후 개선: 주문 이벤트에 개별 상품 가격 정보 추가</li>
     * </ul>
     * </p>
     *
     * @param event 주문 생성 이벤트
     */
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        log.debug("주문 생성 이벤트 처리: orderId={}", event.orderId());
        
        LocalDate date = LocalDate.now();
        
        // 주문 아이템별로 점수 집계
        // 주의: OrderEvent.OrderCreated에는 개별 상품 가격 정보가 없으므로
        // subtotal을 totalQuantity로 나눠서 평균 단가를 구하고, 각 아이템의 quantity를 곱함
        int totalQuantity = event.orderItems().stream()
            .mapToInt(OrderEvent.OrderCreated.OrderItemInfo::quantity)
            .sum();
        
        if (totalQuantity > 0 && event.subtotal() != null) {
            double averagePrice = (double) event.subtotal() / totalQuantity;
            
            for (OrderEvent.OrderCreated.OrderItemInfo item : event.orderItems()) {
                double orderAmount = averagePrice * item.quantity();
                rankingService.addOrderScore(item.productId(), date, orderAmount);
            }
        }
        
        log.debug("주문 점수 추가 완료: orderId={}", event.orderId());
    }

    /**
     * 상품 조회 이벤트를 처리하여 랭킹 점수를 추가합니다.
     *
     * @param event 상품 조회 이벤트
     */
    public void handleProductViewed(ProductEvent.ProductViewed event) {
        log.debug("상품 조회 이벤트 처리: productId={}", event.productId());
        
        LocalDate date = LocalDate.now();
        rankingService.addViewScore(event.productId(), date);
        
        log.debug("조회 점수 추가 완료: productId={}", event.productId());
    }
}

