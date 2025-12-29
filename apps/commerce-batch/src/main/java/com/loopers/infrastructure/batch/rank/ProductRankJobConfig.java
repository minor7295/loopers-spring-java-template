package com.loopers.infrastructure.batch.rank;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.rank.ProductRank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ProductRank 집계를 위한 Spring Batch Job Configuration.
 * <p>
 * 주간/월간 TOP 100 랭킹을 Materialized View에 저장합니다.
 * </p>
 * <p>
 * <b>구현 의도:</b>
 * <ul>
 *   <li><b>주간 집계:</b> 해당 주의 월요일부터 일요일까지의 데이터를 집계</li>
 *   <li><b>월간 집계:</b> 해당 월의 1일부터 마지막 일까지의 데이터를 집계</li>
 *   <li><b>Chunk-Oriented Processing:</b> 대량 데이터를 메모리 효율적으로 처리</li>
 *   <li><b>Materialized View 저장:</b> 조회 성능 최적화를 위한 TOP 100 랭킹 저장</li>
 * </ul>
 * </p>
 * <p>
 * <b>Job 파라미터:</b>
 * <ul>
 *   <li>periodType: 기간 타입 (WEEKLY 또는 MONTHLY)</li>
 *   <li>targetDate: 기준 날짜 (yyyyMMdd 형식, 예: "20241215")</li>
 * </ul>
 * </p>
 * <p>
 * <b>실행 예시:</b>
 * <pre>
 * // 주간 집계
 * java -jar commerce-batch.jar \
 *   --spring.batch.job.names=productRankAggregationJob \
 *   periodType=WEEKLY targetDate=20241215
 *
 * // 월간 집계
 * java -jar commerce-batch.jar \
 *   --spring.batch.job.names=productRankAggregationJob \
 *   periodType=MONTHLY targetDate=20241215
 * </pre>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ProductRankJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ProductRankAggregationReader productRankAggregationReader;
    private final ProductRankAggregationProcessor productRankAggregationProcessor;
    private final ProductRankAggregationWriter productRankAggregationWriter;

    /**
     * ProductRank 집계 Job을 생성합니다.
     *
     * @return ProductRank 집계 Job
     */
    @Bean
    public Job productRankAggregationJob(Step productRankAggregationStep) {
        return new JobBuilder("productRankAggregationJob", jobRepository)
            .start(productRankAggregationStep)
            .build();
    }

    /**
     * ProductRank 집계 Step을 생성합니다.
     * <p>
     * Chunk-Oriented Processing을 사용하여:
     * <ol>
     *   <li>Reader: 특정 기간의 product_metrics를 페이징하여 읽기</li>
     *   <li>Processor: 메트릭을 합산하여 TOP 100 랭킹 생성</li>
     *   <li>Writer: Materialized View에 저장</li>
     * </ol>
     * </p>
     *
     * @param productRankReader ProductRank Reader (StepScope Bean)
     * @param productRankProcessor ProductRank Processor
     * @param productRankWriter ProductRank Writer
     * @return ProductRank 집계 Step
     */
    @Bean
    public Step productRankAggregationStep(
        ItemReader<ProductMetrics> productRankReader,
        ItemProcessor<ProductMetrics, ProductMetrics> productRankProcessor,
        ItemWriter<ProductMetrics> productRankWriter
    ) {
        return new StepBuilder("productRankAggregationStep", jobRepository)
            .<ProductMetrics, ProductMetrics>chunk(100, transactionManager) // Chunk 크기: 100
            .reader(productRankReader)
            .processor(productRankProcessor)
            .writer(productRankWriter)
            .build();
    }

    /**
     * ProductRank Reader를 생성합니다.
     * <p>
     * StepScope로 선언된 Bean이므로 Step 실행 시점에 Job 파라미터를 받아 생성됩니다.
     * </p>
     *
     * @param periodType 기간 타입 (Job 파라미터에서 주입)
     * @param targetDate 기준 날짜 (Job 파라미터에서 주입)
     * @return ProductRank Reader (StepScope로 선언되어 Step 실행 시 생성)
     */
    @Bean
    @StepScope
    public ItemReader<ProductMetrics> productRankReader(
        @Value("#{jobParameters['periodType']}") String periodType,
        @Value("#{jobParameters['targetDate']}") String targetDate
    ) {
        LocalDate date = parseDate(targetDate);
        ProductRank.PeriodType period = ProductRank.PeriodType.valueOf(periodType.toUpperCase());

        // Processor에 기간 정보 설정
        productRankAggregationProcessor.setPeriod(period, date);

        if (period == ProductRank.PeriodType.WEEKLY) {
            return productRankAggregationReader.createWeeklyReader(date);
        } else {
            return productRankAggregationReader.createMonthlyReader(date);
        }
    }

    /**
     * ProductRank Processor를 주입받습니다.
     * <p>
     * 현재는 pass-through이지만, 향후 필터링 로직 추가 가능.
     * </p>
     *
     * @return ProductRank Processor (pass-through)
     */
    @Bean
    public ItemProcessor<ProductMetrics, ProductMetrics> productRankProcessor() {
        return item -> item; // pass-through
    }

    /**
     * ProductRank Writer를 주입받습니다.
     *
     * @return ProductRank Writer
     */
    @Bean
    public ItemWriter<ProductMetrics> productRankWriter() {
        return productRankAggregationWriter;
    }

    /**
     * 날짜 문자열을 LocalDate로 파싱합니다.
     *
     * @param dateStr 날짜 문자열 (yyyyMMdd 형식)
     * @return 파싱된 날짜
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            log.warn("날짜 파라미터가 없어 오늘 날짜를 사용합니다.");
            return LocalDate.now();
        }

        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
        } catch (Exception e) {
            log.warn("날짜 파싱 실패: {}, 오늘 날짜를 사용합니다.", dateStr, e);
            return LocalDate.now();
        }
    }
}

