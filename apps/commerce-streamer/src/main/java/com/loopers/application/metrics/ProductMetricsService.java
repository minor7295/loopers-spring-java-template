package com.loopers.application.metrics;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.metrics.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 메트릭 집계 서비스.
 * <p>
 * Kafka Consumer에서 이벤트를 수취하여 상품 메트릭을 집계합니다.
 * 좋아요 수, 판매량, 상세 페이지 조회 수 등을 upsert합니다.
 * </p>
 * <p>
 * <b>도메인 분리 근거:</b>
 * <ul>
 *   <li>외부 시스템(데이터 플랫폼, 분석 시스템)을 위한 메트릭 집계</li>
 *   <li>Product 도메인의 핵심 비즈니스 로직과는 분리된 관심사</li>
 *   <li>Kafka Consumer를 통한 이벤트 기반 집계</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductMetricsService {

    private final ProductMetricsRepository productMetricsRepository;

    /**
     * 좋아요 수를 증가시킵니다.
     *
     * @param productId 상품 ID
     */
    @Transactional
    public void incrementLikeCount(Long productId) {
        ProductMetrics metrics = findOrCreate(productId);
        metrics.incrementLikeCount();
        productMetricsRepository.save(metrics);
        log.debug("좋아요 수 증가: productId={}, likeCount={}", productId, metrics.getLikeCount());
    }

    /**
     * 좋아요 수를 감소시킵니다.
     *
     * @param productId 상품 ID
     */
    @Transactional
    public void decrementLikeCount(Long productId) {
        ProductMetrics metrics = findOrCreate(productId);
        metrics.decrementLikeCount();
        productMetricsRepository.save(metrics);
        log.debug("좋아요 수 감소: productId={}, likeCount={}", productId, metrics.getLikeCount());
    }

    /**
     * 판매량을 증가시킵니다.
     *
     * @param productId 상품 ID
     * @param quantity 판매 수량
     */
    @Transactional
    public void incrementSalesCount(Long productId, Integer quantity) {
        ProductMetrics metrics = findOrCreate(productId);
        metrics.incrementSalesCount(quantity);
        productMetricsRepository.save(metrics);
        log.debug("판매량 증가: productId={}, quantity={}, salesCount={}", 
            productId, quantity, metrics.getSalesCount());
    }

    /**
     * 상세 페이지 조회 수를 증가시킵니다.
     *
     * @param productId 상품 ID
     */
    @Transactional
    public void incrementViewCount(Long productId) {
        ProductMetrics metrics = findOrCreate(productId);
        metrics.incrementViewCount();
        productMetricsRepository.save(metrics);
        log.debug("조회 수 증가: productId={}, viewCount={}", productId, metrics.getViewCount());
    }

    /**
     * 상품 메트릭을 조회하거나 없으면 생성합니다.
     * <p>
     * 비관적 락을 사용하여 동시성 제어를 보장합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @return ProductMetrics 인스턴스
     */
    private ProductMetrics findOrCreate(Long productId) {
        return productMetricsRepository
            .findByProductIdForUpdate(productId)
            .orElseGet(() -> {
                ProductMetrics newMetrics = new ProductMetrics(productId);
                return productMetricsRepository.save(newMetrics);
            });
    }
}
