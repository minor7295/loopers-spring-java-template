package com.loopers.infrastructure.batch.rank;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.metrics.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;

/**
 * ProductRank 집계를 위한 Spring Batch ItemReader Factory.
 * <p>
 * 주간/월간 집계를 위해 특정 기간의 모든 ProductMetrics를 읽습니다.
 * </p>
 * <p>
 * <b>구현 의도:</b>
 * <ul>
 *   <li>주간 집계: 해당 주의 월요일부터 일요일까지의 데이터 조회</li>
 *   <li>월간 집계: 해당 월의 1일부터 마지막 일까지의 데이터 조회</li>
 *   <li>대량 데이터를 메모리 효율적으로 처리하기 위해 페이징 방식 사용</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductRankAggregationReader {

    private final ProductMetricsRepository productMetricsRepository;

    /**
     * 주간 집계를 위한 Reader를 생성합니다.
     * <p>
     * 해당 주의 월요일부터 일요일까지의 ProductMetrics를 조회합니다.
     * </p>
     *
     * @param targetDate 기준 날짜 (해당 주의 어느 날짜든 가능)
     * @return RepositoryItemReader 인스턴스
     */
    public RepositoryItemReader<ProductMetrics> createWeeklyReader(LocalDate targetDate) {
        // 주간 시작일 계산 (월요일)
        LocalDate weekStart = targetDate.with(java.time.DayOfWeek.MONDAY);
        LocalDateTime startDateTime = weekStart.atStartOfDay();
        
        // 주간 종료일 계산 (다음 주 월요일 00:00:00)
        LocalDate weekEnd = weekStart.plusWeeks(1);
        LocalDateTime endDateTime = weekEnd.atStartOfDay();

        log.info("ProductRank 주간 Reader 초기화: targetDate={}, weekStart={}, weekEnd={}",
            targetDate, weekStart, weekEnd);

        return createReader(startDateTime, endDateTime, "weeklyReader");
    }

    /**
     * 월간 집계를 위한 Reader를 생성합니다.
     * <p>
     * 해당 월의 1일부터 마지막 일까지의 ProductMetrics를 조회합니다.
     * </p>
     *
     * @param targetDate 기준 날짜 (해당 월의 어느 날짜든 가능)
     * @return RepositoryItemReader 인스턴스
     */
    public RepositoryItemReader<ProductMetrics> createMonthlyReader(LocalDate targetDate) {
        // 월간 시작일 계산 (1일)
        LocalDate monthStart = targetDate.with(TemporalAdjusters.firstDayOfMonth());
        LocalDateTime startDateTime = monthStart.atStartOfDay();
        
        // 월간 종료일 계산 (다음 달 1일 00:00:00)
        LocalDate monthEnd = targetDate.with(TemporalAdjusters.firstDayOfNextMonth());
        LocalDateTime endDateTime = monthEnd.atStartOfDay();

        log.info("ProductRank 월간 Reader 초기화: targetDate={}, monthStart={}, monthEnd={}",
            targetDate, monthStart, monthEnd);

        return createReader(startDateTime, endDateTime, "monthlyReader");
    }

    /**
     * ProductMetrics를 읽는 ItemReader를 생성합니다.
     *
     * @param startDateTime 조회 시작 시각
     * @param endDateTime 조회 종료 시각
     * @param readerName Reader 이름
     * @return RepositoryItemReader 인스턴스
     */
    private RepositoryItemReader<ProductMetrics> createReader(
        LocalDateTime startDateTime,
        LocalDateTime endDateTime,
        String readerName
    ) {
        // 정렬 기준 설정 (product_id 기준 오름차순)
        Map<String, Sort.Direction> sorts = new HashMap<>();
        sorts.put("productId", Sort.Direction.ASC);

        // Spring Batch의 RepositoryItemReader는 PagingAndSortingRepository를 직접 요구하므로
        // 기술적 제약으로 인해 getJpaRepository()를 통해 접근
        PagingAndSortingRepository<ProductMetrics, Long> jpaRepository = 
            productMetricsRepository.getJpaRepository();
        
        return new RepositoryItemReaderBuilder<ProductMetrics>()
            .name(readerName)
            .repository(jpaRepository)
            .methodName("findByUpdatedAtBetween")
            .arguments(startDateTime, endDateTime)
            .pageSize(100) // Chunk 크기와 동일하게 설정
            .sorts(sorts)
            .build();
    }
}

