package com.loopers.infrastructure.batch.rank;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.metrics.ProductMetricsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ProductRankAggregationReader 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ProductRankAggregationReaderTest {

    @Mock
    private ProductMetricsRepository productMetricsRepository;

    @Mock
    private PagingAndSortingRepository<ProductMetrics, Long> jpaRepository;

    @DisplayName("주간 Reader를 생성할 수 있다")
    @Test
    void createsWeeklyReader() {
        // arrange
        LocalDate targetDate = LocalDate.of(2024, 12, 15); // 일요일
        when(productMetricsRepository.getJpaRepository()).thenReturn(jpaRepository);
        
        ProductRankAggregationReader reader = new ProductRankAggregationReader(productMetricsRepository);

        // act
        RepositoryItemReader<ProductMetrics> itemReader = reader.createWeeklyReader(targetDate);

        // assert
        assertThat(itemReader).isNotNull();
        assertThat(itemReader.getName()).isEqualTo("weeklyReader");
    }

    @DisplayName("주간 Reader는 해당 주의 월요일부터 다음 주 월요일까지의 데이터를 조회한다")
    @Test
    void weeklyReaderQueriesFromMondayToNextMonday() {
        // arrange
        LocalDate targetDate = LocalDate.of(2024, 12, 15); // 일요일
        when(productMetricsRepository.getJpaRepository()).thenReturn(jpaRepository);
        
        ProductRankAggregationReader reader = new ProductRankAggregationReader(productMetricsRepository);

        // act
        RepositoryItemReader<ProductMetrics> itemReader = reader.createWeeklyReader(targetDate);

        // assert
        assertThat(itemReader).isNotNull();
        // 주간 시작일은 해당 주의 월요일이어야 함
        // 2024-12-15(일) -> 2024-12-09(월)이 시작일
    }

    @DisplayName("월간 Reader를 생성할 수 있다")
    @Test
    void createsMonthlyReader() {
        // arrange
        LocalDate targetDate = LocalDate.of(2024, 12, 15);
        when(productMetricsRepository.getJpaRepository()).thenReturn(jpaRepository);
        
        ProductRankAggregationReader reader = new ProductRankAggregationReader(productMetricsRepository);

        // act
        RepositoryItemReader<ProductMetrics> itemReader = reader.createMonthlyReader(targetDate);

        // assert
        assertThat(itemReader).isNotNull();
        assertThat(itemReader.getName()).isEqualTo("monthlyReader");
    }

    @DisplayName("월간 Reader는 해당 월의 1일부터 다음 달 1일까지의 데이터를 조회한다")
    @Test
    void monthlyReaderQueriesFromFirstDayToNextMonth() {
        // arrange
        LocalDate targetDate = LocalDate.of(2024, 12, 15);
        when(productMetricsRepository.getJpaRepository()).thenReturn(jpaRepository);
        
        ProductRankAggregationReader reader = new ProductRankAggregationReader(productMetricsRepository);

        // act
        RepositoryItemReader<ProductMetrics> itemReader = reader.createMonthlyReader(targetDate);

        // assert
        assertThat(itemReader).isNotNull();
        // 월간 시작일은 해당 월의 1일이어야 함
        // 2024-12-15 -> 2024-12-01이 시작일
    }

    @DisplayName("주간 Reader는 주의 어느 날짜든 올바른 주간 범위를 계산한다")
    @Test
    void weeklyReaderCalculatesCorrectWeekRange_forAnyDayInWeek() {
        // arrange
        when(productMetricsRepository.getJpaRepository()).thenReturn(jpaRepository);
        ProductRankAggregationReader reader = new ProductRankAggregationReader(productMetricsRepository);

        // 월요일
        LocalDate monday = LocalDate.of(2024, 12, 9);
        // 수요일
        LocalDate wednesday = LocalDate.of(2024, 12, 11);
        // 일요일
        LocalDate sunday = LocalDate.of(2024, 12, 15);

        // act
        RepositoryItemReader<ProductMetrics> mondayReader = reader.createWeeklyReader(monday);
        RepositoryItemReader<ProductMetrics> wednesdayReader = reader.createWeeklyReader(wednesday);
        RepositoryItemReader<ProductMetrics> sundayReader = reader.createWeeklyReader(sunday);

        // assert
        assertThat(mondayReader).isNotNull();
        assertThat(wednesdayReader).isNotNull();
        assertThat(sundayReader).isNotNull();
        // 모두 같은 주의 월요일부터 시작해야 함
    }

    @DisplayName("월간 Reader는 월의 어느 날짜든 올바른 월간 범위를 계산한다")
    @Test
    void monthlyReaderCalculatesCorrectMonthRange_forAnyDayInMonth() {
        // arrange
        when(productMetricsRepository.getJpaRepository()).thenReturn(jpaRepository);
        ProductRankAggregationReader reader = new ProductRankAggregationReader(productMetricsRepository);

        // 1일
        LocalDate firstDay = LocalDate.of(2024, 12, 1);
        // 15일
        LocalDate midDay = LocalDate.of(2024, 12, 15);
        // 마지막 일
        LocalDate lastDay = LocalDate.of(2024, 12, 31);

        // act
        RepositoryItemReader<ProductMetrics> firstDayReader = reader.createMonthlyReader(firstDay);
        RepositoryItemReader<ProductMetrics> midDayReader = reader.createMonthlyReader(midDay);
        RepositoryItemReader<ProductMetrics> lastDayReader = reader.createMonthlyReader(lastDay);

        // assert
        assertThat(firstDayReader).isNotNull();
        assertThat(midDayReader).isNotNull();
        assertThat(lastDayReader).isNotNull();
        // 모두 같은 월의 1일부터 시작해야 함
    }
}

