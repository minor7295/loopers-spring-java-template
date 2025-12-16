package com.loopers.domain.metrics;

import java.util.Optional;

/**
 * ProductMetrics 엔티티에 대한 저장소 인터페이스.
 * <p>
 * 상품 메트릭 집계 데이터의 영속성 계층과의 상호작용을 정의합니다.
 * DIP를 준수하여 도메인 레이어에서 인터페이스를 정의합니다.
 * </p>
 * <p>
 * <b>도메인 분리 근거:</b>
 * <ul>
 *   <li>Metric 도메인은 외부 시스템 연동을 위한 별도 관심사</li>
 *   <li>Product 도메인의 핵심 비즈니스 로직과는 분리</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface ProductMetricsRepository {

    /**
     * 상품 메트릭을 저장합니다.
     *
     * @param productMetrics 저장할 상품 메트릭
     * @return 저장된 상품 메트릭
     */
    ProductMetrics save(ProductMetrics productMetrics);

    /**
     * 상품 ID로 메트릭을 조회합니다.
     *
     * @param productId 상품 ID
     * @return 조회된 메트릭을 담은 Optional
     */
    Optional<ProductMetrics> findByProductId(Long productId);

    /**
     * 상품 ID로 메트릭을 조회합니다. (비관적 락)
     * <p>
     * Upsert 시 동시성 제어를 위해 사용합니다.
     * </p>
     * <p>
     * <b>Lock 전략:</b>
     * <ul>
     *   <li><b>PESSIMISTIC_WRITE:</b> SELECT ... FOR UPDATE 사용</li>
     *   <li><b>Lock 범위:</b> PK(productId) 기반 조회로 해당 행만 락 (최소화)</li>
     *   <li><b>사용 목적:</b> 메트릭 집계 시 Lost Update 방지</li>
     * </ul>
     * </p>
     *
     * @param productId 상품 ID
     * @return 조회된 메트릭을 담은 Optional
     */
    Optional<ProductMetrics> findByProductIdForUpdate(Long productId);
}
