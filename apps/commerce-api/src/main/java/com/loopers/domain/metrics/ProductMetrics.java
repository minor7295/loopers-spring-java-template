package com.loopers.domain.metrics;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 상품 메트릭 집계 엔티티.
 * <p>
 * Kafka Consumer에서 이벤트를 수취하여 집계한 메트릭을 저장합니다.
 * 좋아요 수, 판매량, 상세 페이지 조회 수 등을 관리합니다.
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
@Entity
@Table(name = "product_metrics")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class ProductMetrics {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "like_count", nullable = false)
    private Long likeCount;

    @Column(name = "sales_count", nullable = false)
    private Long salesCount;

    @Column(name = "view_count", nullable = false)
    private Long viewCount;

    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * ProductMetrics 인스턴스를 생성합니다.
     *
     * @param productId 상품 ID
     */
    public ProductMetrics(Long productId) {
        this.productId = productId;
        this.likeCount = 0L;
        this.salesCount = 0L;
        this.viewCount = 0L;
        this.version = 0L;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 좋아요 수를 증가시킵니다.
     */
    public void incrementLikeCount() {
        this.likeCount++;
        this.version++;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 좋아요 수를 감소시킵니다.
     */
    public void decrementLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
            this.version++;
            this.updatedAt = LocalDateTime.now();
        }
    }

    /**
     * 판매량을 증가시킵니다.
     *
     * @param quantity 판매 수량
     */
    public void incrementSalesCount(Integer quantity) {
        if (quantity != null && quantity > 0) {
            this.salesCount += quantity;
            this.version++;
            this.updatedAt = LocalDateTime.now();
        }
    }

    /**
     * 상세 페이지 조회 수를 증가시킵니다.
     */
    public void incrementViewCount() {
        this.viewCount++;
        this.version++;
        this.updatedAt = LocalDateTime.now();
    }
}
