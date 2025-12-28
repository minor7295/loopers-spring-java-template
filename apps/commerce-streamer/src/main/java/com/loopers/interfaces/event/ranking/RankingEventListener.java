package com.loopers.interfaces.event.ranking;

import com.loopers.application.ranking.RankingEventHandler;
import com.loopers.domain.event.LikeEvent;
import com.loopers.domain.event.OrderEvent;
import com.loopers.domain.event.ProductEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 랭킹 이벤트 리스너.
 * <p>
 * 좋아요 추가/취소, 주문 생성, 상품 조회 이벤트를 받아서 랭킹 점수를 집계하는 인터페이스 레이어의 어댑터입니다.
 * </p>
 * <p>
 * <b>레이어 역할:</b>
 * <ul>
 *   <li><b>인터페이스 레이어:</b> 외부 이벤트(도메인 이벤트)를 받아서 애플리케이션 핸들러를 호출하는 어댑터</li>
 *   <li><b>비즈니스 로직 없음:</b> 단순히 이벤트를 받아서 애플리케이션 핸들러를 호출하는 역할만 수행</li>
 * </ul>
 * </p>
 * <p>
 * <b>EDA 원칙:</b>
 * <ul>
 *   <li><b>비동기 처리:</b> @Async로 집계 처리를 비동기로 실행하여 Kafka Consumer의 성능에 영향 없음</li>
 *   <li><b>이벤트 기반:</b> 좋아요, 주문, 조회 이벤트를 구독하여 랭킹 점수 집계</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingEventListener {

    private final RankingEventHandler rankingEventHandler;

    /**
     * 좋아요 추가 이벤트를 처리합니다.
     * <p>
     * 비동기로 실행되어 랭킹 점수를 집계합니다.
     * </p>
     *
     * @param event 좋아요 추가 이벤트
     */
    @Async
    @EventListener
    public void handleLikeAdded(LikeEvent.LikeAdded event) {
        try {
            rankingEventHandler.handleLikeAdded(event);
        } catch (Exception e) {
            log.error("좋아요 추가 이벤트 처리 중 오류 발생: productId={}, userId={}", 
                event.productId(), event.userId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }

    /**
     * 좋아요 취소 이벤트를 처리합니다.
     * <p>
     * 비동기로 실행되어 랭킹 점수를 차감합니다.
     * </p>
     *
     * @param event 좋아요 취소 이벤트
     */
    @Async
    @EventListener
    public void handleLikeRemoved(LikeEvent.LikeRemoved event) {
        try {
            rankingEventHandler.handleLikeRemoved(event);
        } catch (Exception e) {
            log.error("좋아요 취소 이벤트 처리 중 오류 발생: productId={}, userId={}", 
                event.productId(), event.userId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }

    /**
     * 주문 생성 이벤트를 처리합니다.
     * <p>
     * 비동기로 실행되어 랭킹 점수를 집계합니다.
     * </p>
     *
     * @param event 주문 생성 이벤트
     */
    @Async
    @EventListener
    public void handleOrderCreated(OrderEvent.OrderCreated event) {
        try {
            rankingEventHandler.handleOrderCreated(event);
        } catch (Exception e) {
            log.error("주문 생성 이벤트 처리 중 오류 발생: orderId={}", event.orderId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }

    /**
     * 상품 조회 이벤트를 처리합니다.
     * <p>
     * 비동기로 실행되어 랭킹 점수를 집계합니다.
     * </p>
     *
     * @param event 상품 조회 이벤트
     */
    @Async
    @EventListener
    public void handleProductViewed(ProductEvent.ProductViewed event) {
        try {
            rankingEventHandler.handleProductViewed(event);
        } catch (Exception e) {
            log.error("상품 조회 이벤트 처리 중 오류 발생: productId={}", event.productId(), e);
            // 이벤트 처리 실패는 다른 리스너에 영향을 주지 않도록 예외를 삼킴
        }
    }
}

