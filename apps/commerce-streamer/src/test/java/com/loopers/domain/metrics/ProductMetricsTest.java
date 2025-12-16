package com.loopers.domain.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

public class ProductMetricsTest {

    @DisplayName("ProductMetrics는 상품 ID로 생성되며 초기값이 0으로 설정된다.")
    @Test
    void createsProductMetricsWithInitialValues() {
        // arrange
        Long productId = 1L;

        // act
        ProductMetrics metrics = new ProductMetrics(productId);

        // assert
        assertThat(metrics.getProductId()).isEqualTo(productId);
        assertThat(metrics.getLikeCount()).isEqualTo(0L);
        assertThat(metrics.getSalesCount()).isEqualTo(0L);
        assertThat(metrics.getViewCount()).isEqualTo(0L);
        assertThat(metrics.getVersion()).isEqualTo(0L);
        assertThat(metrics.getUpdatedAt()).isNotNull();
    }

    @DisplayName("좋아요 수를 증가시킬 수 있다.")
    @Test
    void canIncrementLikeCount() throws InterruptedException {
        // arrange
        ProductMetrics metrics = new ProductMetrics(1L);
        Long initialLikeCount = metrics.getLikeCount();
        Long initialVersion = metrics.getVersion();
        LocalDateTime initialUpdatedAt = metrics.getUpdatedAt();

        // act
        Thread.sleep(1); // 시간 차이를 보장하기 위한 작은 지연
        metrics.incrementLikeCount();

        // assert
        assertThat(metrics.getLikeCount()).isEqualTo(initialLikeCount + 1);
        assertThat(metrics.getVersion()).isEqualTo(initialVersion + 1);
        assertThat(metrics.getUpdatedAt()).isAfter(initialUpdatedAt);
    }

    @DisplayName("좋아요 수를 감소시킬 수 있다.")
    @Test
    void canDecrementLikeCount() {
        // arrange
        ProductMetrics metrics = new ProductMetrics(1L);
        metrics.incrementLikeCount(); // 먼저 증가시킴
        Long initialLikeCount = metrics.getLikeCount();
        Long initialVersion = metrics.getVersion();

        // act
        metrics.decrementLikeCount();

        // assert
        assertThat(metrics.getLikeCount()).isEqualTo(initialLikeCount - 1);
        assertThat(metrics.getVersion()).isEqualTo(initialVersion + 1);
    }

    @DisplayName("좋아요 수가 0일 때 감소해도 음수가 되지 않는다 (멱등성 보장).")
    @Test
    void preventsNegativeLikeCount_whenDecrementingFromZero() {
        // arrange
        ProductMetrics metrics = new ProductMetrics(1L);
        assertThat(metrics.getLikeCount()).isEqualTo(0L);
        Long initialVersion = metrics.getVersion();

        // act
        metrics.decrementLikeCount();

        // assert
        assertThat(metrics.getLikeCount()).isEqualTo(0L);
        assertThat(metrics.getVersion()).isEqualTo(initialVersion); // version도 변경되지 않음
    }

    @DisplayName("판매량을 증가시킬 수 있다.")
    @Test
    void canIncrementSalesCount() {
        // arrange
        ProductMetrics metrics = new ProductMetrics(1L);
        Long initialSalesCount = metrics.getSalesCount();
        Long initialVersion = metrics.getVersion();
        Integer quantity = 5;

        // act
        metrics.incrementSalesCount(quantity);

        // assert
        assertThat(metrics.getSalesCount()).isEqualTo(initialSalesCount + quantity);
        assertThat(metrics.getVersion()).isEqualTo(initialVersion + 1);
    }

    @DisplayName("판매량 증가 시 null이나 0 이하의 수량은 무시된다.")
    @Test
    void ignoresInvalidQuantity_whenIncrementingSalesCount() {
        // arrange
        ProductMetrics metrics = new ProductMetrics(1L);
        Long initialSalesCount = metrics.getSalesCount();
        Long initialVersion = metrics.getVersion();

        // act
        metrics.incrementSalesCount(null);
        metrics.incrementSalesCount(0);
        metrics.incrementSalesCount(-1);

        // assert
        assertThat(metrics.getSalesCount()).isEqualTo(initialSalesCount);
        assertThat(metrics.getVersion()).isEqualTo(initialVersion); // version도 변경되지 않음
    }

    @DisplayName("상세 페이지 조회 수를 증가시킬 수 있다.")
    @Test
    void canIncrementViewCount() throws InterruptedException {
        // arrange
        ProductMetrics metrics = new ProductMetrics(1L);
        Long initialViewCount = metrics.getViewCount();
        Long initialVersion = metrics.getVersion();
        LocalDateTime initialUpdatedAt = metrics.getUpdatedAt();

        // act
        Thread.sleep(1); // 시간 차이를 보장하기 위한 작은 지연
        metrics.incrementViewCount();

        // assert
        assertThat(metrics.getViewCount()).isEqualTo(initialViewCount + 1);
        assertThat(metrics.getVersion()).isEqualTo(initialVersion + 1);
        assertThat(metrics.getUpdatedAt()).isAfter(initialUpdatedAt);
    }

    @DisplayName("여러 메트릭을 연속으로 업데이트할 수 있다.")
    @Test
    void canUpdateMultipleMetrics() {
        // arrange
        ProductMetrics metrics = new ProductMetrics(1L);

        // act
        metrics.incrementLikeCount();
        metrics.incrementLikeCount();
        metrics.incrementSalesCount(10);
        metrics.incrementViewCount();
        metrics.decrementLikeCount();

        // assert
        assertThat(metrics.getLikeCount()).isEqualTo(1L);
        assertThat(metrics.getSalesCount()).isEqualTo(10L);
        assertThat(metrics.getViewCount()).isEqualTo(1L);
        assertThat(metrics.getVersion()).isEqualTo(5L); // 5번 업데이트됨
    }
}
