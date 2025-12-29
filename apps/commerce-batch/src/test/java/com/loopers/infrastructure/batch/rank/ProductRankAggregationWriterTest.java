package com.loopers.infrastructure.batch.rank;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.rank.ProductRank;
import com.loopers.domain.rank.ProductRankRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ProductRankAggregationWriter 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ProductRankAggregationWriterTest {

    @Mock
    private ProductRankRepository productRankRepository;

    @Mock
    private ProductRankAggregationProcessor productRankAggregationProcessor;

    @InjectMocks
    private ProductRankAggregationWriter writer;

    @DisplayName("Chunk를 정상적으로 처리할 수 있다")
    @Test
    void writesChunk_successfully() throws Exception {
        // arrange
        ProductRank.PeriodType periodType = ProductRank.PeriodType.WEEKLY;
        LocalDate periodStartDate = LocalDate.of(2024, 12, 9);

        when(productRankAggregationProcessor.getPeriodType()).thenReturn(periodType);
        when(productRankAggregationProcessor.getPeriodStartDate()).thenReturn(periodStartDate);

        List<ProductMetrics> items = createProductMetricsList(3);
        Chunk<ProductMetrics> chunk = new Chunk<>(items);

        // act
        writer.write(chunk);

        // assert
        ArgumentCaptor<List<ProductRank>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(productRankRepository, times(1))
            .saveRanks(eq(periodType), eq(periodStartDate), ranksCaptor.capture());

        List<ProductRank> savedRanks = ranksCaptor.getValue();
        assertThat(savedRanks).isNotEmpty();
    }

    @DisplayName("빈 Chunk는 처리하지 않는다")
    @Test
    void skipsEmptyChunk() throws Exception {
        // arrange
        Chunk<ProductMetrics> chunk = new Chunk<>(new ArrayList<>());

        // act
        writer.write(chunk);

        // assert
        verify(productRankRepository, never()).saveRanks(any(), any(), any());
    }

    @DisplayName("기간 정보가 설정되지 않으면 처리하지 않는다")
    @Test
    void skipsProcessing_whenPeriodNotSet() throws Exception {
        // arrange
        when(productRankAggregationProcessor.getPeriodType()).thenReturn(null);
        when(productRankAggregationProcessor.getPeriodStartDate()).thenReturn(null);

        List<ProductMetrics> items = createProductMetricsList(3);
        Chunk<ProductMetrics> chunk = new Chunk<>(items);

        // act
        writer.write(chunk);

        // assert
        verify(productRankRepository, never()).saveRanks(any(), any(), any());
    }

    @DisplayName("같은 product_id를 가진 메트릭을 합산한다")
    @Test
    void aggregatesMetricsByProductId() throws Exception {
        // arrange
        ProductRank.PeriodType periodType = ProductRank.PeriodType.WEEKLY;
        LocalDate periodStartDate = LocalDate.of(2024, 12, 9);

        when(productRankAggregationProcessor.getPeriodType()).thenReturn(periodType);
        when(productRankAggregationProcessor.getPeriodStartDate()).thenReturn(periodStartDate);

        // 같은 productId를 가진 여러 메트릭
        List<ProductMetrics> items = new ArrayList<>();
        ProductMetrics metrics1 = new ProductMetrics(1L);
        metrics1.incrementLikeCount();
        metrics1.incrementSalesCount(10);
        items.add(metrics1);

        ProductMetrics metrics2 = new ProductMetrics(1L); // 같은 productId
        metrics2.incrementLikeCount();
        metrics2.incrementSalesCount(20);
        items.add(metrics2);

        ProductMetrics metrics3 = new ProductMetrics(2L); // 다른 productId
        metrics3.incrementLikeCount();
        items.add(metrics3);

        Chunk<ProductMetrics> chunk = new Chunk<>(items);

        // act
        writer.write(chunk);

        // assert
        ArgumentCaptor<List<ProductRank>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(productRankRepository, times(1))
            .saveRanks(eq(periodType), eq(periodStartDate), ranksCaptor.capture());

        List<ProductRank> savedRanks = ranksCaptor.getValue();
        assertThat(savedRanks).hasSize(2); // productId 1과 2

        // productId 1의 메트릭이 합산되었는지 확인
        ProductRank rank1 = savedRanks.stream()
            .filter(r -> r.getProductId().equals(1L))
            .findFirst()
            .orElseThrow();
        assertThat(rank1.getLikeCount()).isEqualTo(2L); // 1 + 1
        assertThat(rank1.getSalesCount()).isEqualTo(30L); // 10 + 20
    }

    @DisplayName("종합 점수 기준으로 TOP 100을 선정한다")
    @Test
    void selectsTop100ByScore() throws Exception {
        // arrange
        ProductRank.PeriodType periodType = ProductRank.PeriodType.WEEKLY;
        LocalDate periodStartDate = LocalDate.of(2024, 12, 9);

        when(productRankAggregationProcessor.getPeriodType()).thenReturn(periodType);
        when(productRankAggregationProcessor.getPeriodStartDate()).thenReturn(periodStartDate);

        // 150개의 메트릭 생성 (TOP 100만 선택되어야 함)
        List<ProductMetrics> items = createProductMetricsList(150);
        Chunk<ProductMetrics> chunk = new Chunk<>(items);

        // act
        writer.write(chunk);

        // assert
        ArgumentCaptor<List<ProductRank>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(productRankRepository, times(1))
            .saveRanks(eq(periodType), eq(periodStartDate), ranksCaptor.capture());

        List<ProductRank> savedRanks = ranksCaptor.getValue();
        assertThat(savedRanks).hasSizeLessThanOrEqualTo(100);
    }

    @DisplayName("랭킹을 1부터 시작하여 부여한다")
    @Test
    void assignsRanksStartingFromOne() throws Exception {
        // arrange
        ProductRank.PeriodType periodType = ProductRank.PeriodType.WEEKLY;
        LocalDate periodStartDate = LocalDate.of(2024, 12, 9);

        when(productRankAggregationProcessor.getPeriodType()).thenReturn(periodType);
        when(productRankAggregationProcessor.getPeriodStartDate()).thenReturn(periodStartDate);

        List<ProductMetrics> items = createProductMetricsList(5);
        Chunk<ProductMetrics> chunk = new Chunk<>(items);

        // act
        writer.write(chunk);

        // assert
        ArgumentCaptor<List<ProductRank>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(productRankRepository, times(1))
            .saveRanks(eq(periodType), eq(periodStartDate), ranksCaptor.capture());

        List<ProductRank> savedRanks = ranksCaptor.getValue();
        assertThat(savedRanks).extracting(ProductRank::getRank)
            .containsExactly(1, 2, 3, 4, 5);
    }

    @DisplayName("주간 랭킹을 저장한다")
    @Test
    void savesWeeklyRanks() throws Exception {
        // arrange
        ProductRank.PeriodType periodType = ProductRank.PeriodType.WEEKLY;
        LocalDate periodStartDate = LocalDate.of(2024, 12, 9);

        when(productRankAggregationProcessor.getPeriodType()).thenReturn(periodType);
        when(productRankAggregationProcessor.getPeriodStartDate()).thenReturn(periodStartDate);

        List<ProductMetrics> items = createProductMetricsList(3);
        Chunk<ProductMetrics> chunk = new Chunk<>(items);

        // act
        writer.write(chunk);

        // assert
        verify(productRankRepository, times(1))
            .saveRanks(eq(ProductRank.PeriodType.WEEKLY), eq(periodStartDate), any());
    }

    @DisplayName("월간 랭킹을 저장한다")
    @Test
    void savesMonthlyRanks() throws Exception {
        // arrange
        ProductRank.PeriodType periodType = ProductRank.PeriodType.MONTHLY;
        LocalDate periodStartDate = LocalDate.of(2024, 12, 1);

        when(productRankAggregationProcessor.getPeriodType()).thenReturn(periodType);
        when(productRankAggregationProcessor.getPeriodStartDate()).thenReturn(periodStartDate);

        List<ProductMetrics> items = createProductMetricsList(3);
        Chunk<ProductMetrics> chunk = new Chunk<>(items);

        // act
        writer.write(chunk);

        // assert
        verify(productRankRepository, times(1))
            .saveRanks(eq(ProductRank.PeriodType.MONTHLY), eq(periodStartDate), any());
    }

    /**
     * 테스트용 ProductMetrics 리스트를 생성합니다.
     *
     * @param count 생성할 항목 수
     * @return ProductMetrics 리스트
     */
    private List<ProductMetrics> createProductMetricsList(int count) {
        List<ProductMetrics> items = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            ProductMetrics metrics = new ProductMetrics(i);
            // 점수가 높은 순서로 생성 (i가 클수록 점수가 높음)
            metrics.incrementLikeCount();
            metrics.incrementSalesCount((int) (i * 10));
            metrics.incrementViewCount();
            items.add(metrics);
        }
        return items;
    }
}

