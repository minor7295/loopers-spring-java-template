package com.loopers.application.metrics;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.metrics.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
     * <p>
     * 이벤트의 버전을 기준으로 최신 이벤트만 반영합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param eventVersion 이벤트의 버전
     */
    @Transactional
    public void incrementLikeCount(Long productId, Long eventVersion) {
        ProductMetrics metrics = findOrCreate(productId);
        
        // 버전 비교: 이벤트가 최신이 아니면 스킵
        if (!metrics.shouldUpdate(eventVersion)) {
            log.debug("오래된 이벤트 스킵: productId={}, eventVersion={}, metricsVersion={}", 
                productId, eventVersion, metrics.getVersion());
            return;
        }
        
        metrics.incrementLikeCount();
        productMetricsRepository.save(metrics);
        log.debug("좋아요 수 증가: productId={}, likeCount={}", productId, metrics.getLikeCount());
    }

    /**
     * 좋아요 수를 감소시킵니다.
     * <p>
     * 이벤트의 버전을 기준으로 최신 이벤트만 반영합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param eventVersion 이벤트의 버전
     */
    @Transactional
    public void decrementLikeCount(Long productId, Long eventVersion) {
        ProductMetrics metrics = findOrCreate(productId);
        
        // 버전 비교: 이벤트가 최신이 아니면 스킵
        if (!metrics.shouldUpdate(eventVersion)) {
            log.debug("오래된 이벤트 스킵: productId={}, eventVersion={}, metricsVersion={}", 
                productId, eventVersion, metrics.getVersion());
            return;
        }
        
        metrics.decrementLikeCount();
        productMetricsRepository.save(metrics);
        log.debug("좋아요 수 감소: productId={}, likeCount={}", productId, metrics.getLikeCount());
    }

    /**
     * 판매량을 증가시킵니다.
     * <p>
     * 이벤트의 버전을 기준으로 최신 이벤트만 반영합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param quantity 판매 수량
     * @param eventVersion 이벤트의 버전
     */
    @Transactional
    public void incrementSalesCount(Long productId, Integer quantity, Long eventVersion) {
        ProductMetrics metrics = findOrCreate(productId);
        
        // 버전 비교: 이벤트가 최신이 아니면 스킵
        if (!metrics.shouldUpdate(eventVersion)) {
            log.debug("오래된 이벤트 스킵: productId={}, eventVersion={}, metricsVersion={}", 
                productId, eventVersion, metrics.getVersion());
            return;
        }
        
        metrics.incrementSalesCount(quantity);
        productMetricsRepository.save(metrics);
        log.debug("판매량 증가: productId={}, quantity={}, salesCount={}", 
            productId, quantity, metrics.getSalesCount());
    }

    /**
     * 상세 페이지 조회 수를 증가시킵니다.
     * <p>
     * 이벤트의 버전을 기준으로 최신 이벤트만 반영합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param eventVersion 이벤트의 버전
     */
    @Transactional
    public void incrementViewCount(Long productId, Long eventVersion) {
        ProductMetrics metrics = findOrCreate(productId);
        
        // 버전 비교: 이벤트가 최신이 아니면 스킵
        if (!metrics.shouldUpdate(eventVersion)) {
            log.debug("오래된 이벤트 스킵: productId={}, eventVersion={}, metricsVersion={}", 
                productId, eventVersion, metrics.getVersion());
            return;
        }
        
        metrics.incrementViewCount();
        productMetricsRepository.save(metrics);
        log.debug("조회 수 증가: productId={}, viewCount={}", productId, metrics.getViewCount());
    }

    /**
     * 상품 메트릭을 조회하거나 없으면 생성합니다.
     * <p>
     * 비관적 락을 사용하여 동시성 제어를 보장합니다.
     * 신규 생성 시 동시 삽입으로 인한 unique constraint violation을 처리합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @return ProductMetrics 인스턴스
     */
    private ProductMetrics findOrCreate(Long productId) {
        return productMetricsRepository
            .findByProductIdForUpdate(productId)
            .orElseGet(() -> {
                try {
                    ProductMetrics newMetrics = new ProductMetrics(productId);
                    return productMetricsRepository.save(newMetrics);
                } catch (DataIntegrityViolationException e) {
                    // 동시 삽입 시 재조회
                    log.debug("동시 삽입 감지, 재조회: productId={}", productId);
                    return productMetricsRepository
                        .findByProductIdForUpdate(productId)
                        .orElseThrow(() -> new IllegalStateException(
                            "ProductMetrics 생성 실패: productId=" + productId));
                }
            });
    }
}
