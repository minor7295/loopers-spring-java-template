package com.loopers.infrastructure.metrics;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.metrics.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ProductMetricsRepository의 JPA 구현체.
 * <p>
 * Spring Data JPA를 활용하여 ProductMetrics 엔티티의
 * 영속성 작업을 처리합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class ProductMetricsRepositoryImpl implements ProductMetricsRepository {

    private final ProductMetricsJpaRepository productMetricsJpaRepository;

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductMetrics save(ProductMetrics productMetrics) {
        return productMetricsJpaRepository.save(productMetrics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ProductMetrics> findByProductId(Long productId) {
        return productMetricsJpaRepository.findByProductId(productId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ProductMetrics> findByProductIdForUpdate(Long productId) {
        return productMetricsJpaRepository.findByProductIdForUpdate(productId);
    }
}
