package com.loopers.infrastructure.metrics;

import com.loopers.domain.metrics.ProductMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

/**
 * ProductMetrics JPA Repository.
 * <p>
 * 상품 메트릭 집계 데이터를 관리합니다.
 * </p>
 */
public interface ProductMetricsJpaRepository extends JpaRepository<ProductMetrics, Long> {

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
     *
     * @param productId 상품 ID
     * @return 조회된 메트릭을 담은 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pm FROM ProductMetrics pm WHERE pm.productId = :productId")
    Optional<ProductMetrics> findByProductIdForUpdate(@Param("productId") Long productId);
}
