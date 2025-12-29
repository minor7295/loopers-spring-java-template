package com.loopers.infrastructure.batch.rank;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.rank.ProductRank;
import com.loopers.domain.rank.ProductRankRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * ProductRank를 Materialized View에 저장하는 Writer.
 * <p>
 * 주간/월간 TOP 100 랭킹을 Materialized View에 저장합니다.
 * </p>
 * <p>
 * <b>구현 의도:</b>
 * <ul>
 *   <li>Chunk 단위로 받은 ProductMetrics를 집계하여 TOP 100 랭킹 생성</li>
 *   <li>기존 데이터 삭제 후 새 데이터 저장 (UPSERT 방식)</li>
 *   <li>주간/월간 랭킹을 별도로 관리</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductRankAggregationWriter implements ItemWriter<ProductMetrics> {

    private final ProductRankRepository productRankRepository;
    private final ProductRankAggregationProcessor productRankAggregationProcessor;

    /**
     * ProductMetrics Chunk를 집계하여 Materialized View에 저장합니다.
     * <p>
     * Chunk 단위로 받은 ProductMetrics를 집계하여 TOP 100 랭킹을 생성하고 저장합니다.
     * </p>
     *
     * @param chunk 처리할 ProductMetrics Chunk
     * @throws Exception 처리 중 오류 발생 시
     */
    @Override
    public void write(Chunk<? extends ProductMetrics> chunk) throws Exception {
        List<? extends ProductMetrics> items = chunk.getItems();

        if (items.isEmpty()) {
            log.warn("ProductMetrics Chunk가 비어있습니다.");
            return;
        }

        log.info("ProductRank Chunk 처리 시작: itemCount={}", items.size());

        // Processor에서 기간 정보 가져오기
        ProductRank.PeriodType periodType = productRankAggregationProcessor.getPeriodType();
        LocalDate periodStartDate = productRankAggregationProcessor.getPeriodStartDate();

        if (periodType == null || periodStartDate == null) {
            log.error("기간 정보가 설정되지 않았습니다. 건너뜁니다.");
            return;
        }

        // 같은 product_id를 가진 메트릭을 합산
        Map<Long, AggregatedMetrics> aggregatedMap = items.stream()
            .collect(Collectors.groupingBy(
                ProductMetrics::getProductId,
                Collectors.reducing(
                    new AggregatedMetrics(0L, 0L, 0L),
                    metrics -> new AggregatedMetrics(
                        metrics.getLikeCount(),
                        metrics.getSalesCount(),
                        metrics.getViewCount()
                    ),
                    (a, b) -> new AggregatedMetrics(
                        a.getLikeCount() + b.getLikeCount(),
                        a.getSalesCount() + b.getSalesCount(),
                        a.getViewCount() + b.getViewCount()
                    )
                )
            ));

        // 종합 점수 계산 및 정렬하여 TOP 100 선정
        List<ProductRank> ranks = aggregatedMap.entrySet().stream()
            .map(entry -> {
                Long productId = entry.getKey();
                AggregatedMetrics aggregated = entry.getValue();
                double score = calculateScore(aggregated);
                return new RankedMetrics(productId, aggregated, score);
            })
            .sorted(Comparator.comparing(RankedMetrics::getScore).reversed())
            .limit(100) // TOP 100
            .collect(Collectors.collectingAndThen(
                Collectors.toList(),
                rankedList -> IntStream.range(0, rankedList.size())
                    .mapToObj(i -> {
                        RankedMetrics ranked = rankedList.get(i);
                        AggregatedMetrics aggregated = ranked.getAggregated();
                        return new ProductRank(
                            periodType,
                            periodStartDate,
                            ranked.getProductId(),
                            i + 1, // 랭킹 (1부터 시작)
                            aggregated.getLikeCount(),
                            aggregated.getSalesCount(),
                            aggregated.getViewCount()
                        );
                    })
                    .collect(Collectors.toList())
            ));

        // Materialized View에 저장
        productRankRepository.saveRanks(periodType, periodStartDate, ranks);

        log.info("ProductRank 저장 완료: periodType={}, periodStartDate={}, rankCount={}",
            periodType, periodStartDate, ranks.size());
    }

    /**
     * 종합 점수를 계산합니다.
     * <p>
     * 가중치:
     * <ul>
     *   <li>좋아요: 0.3</li>
     *   <li>판매량: 0.5</li>
     *   <li>조회수: 0.2</li>
     * </ul>
     * </p>
     *
     * @param aggregated AggregatedMetrics
     * @return 종합 점수
     */
    private double calculateScore(AggregatedMetrics aggregated) {
        return aggregated.getLikeCount() * 0.3
            + aggregated.getSalesCount() * 0.5
            + aggregated.getViewCount() * 0.2;
    }

    /**
     * 랭킹이 부여된 메트릭을 담는 내부 클래스.
     */
    private static class RankedMetrics {
        private final Long productId;
        private final AggregatedMetrics aggregated;
        private final double score;

        public RankedMetrics(Long productId, AggregatedMetrics aggregated, double score) {
            this.productId = productId;
            this.aggregated = aggregated;
            this.score = score;
        }

        public Long getProductId() {
            return productId;
        }

        public AggregatedMetrics getAggregated() {
            return aggregated;
        }

        public double getScore() {
            return score;
        }
    }

    /**
     * 집계된 메트릭을 담는 내부 클래스.
     */
    private static class AggregatedMetrics {
        private final Long likeCount;
        private final Long salesCount;
        private final Long viewCount;

        public AggregatedMetrics(Long likeCount, Long salesCount, Long viewCount) {
            this.likeCount = likeCount;
            this.salesCount = salesCount;
            this.viewCount = viewCount;
        }

        public Long getLikeCount() {
            return likeCount;
        }

        public Long getSalesCount() {
            return salesCount;
        }

        public Long getViewCount() {
            return viewCount;
        }
    }
}

