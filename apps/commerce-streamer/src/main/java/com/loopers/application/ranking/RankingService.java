package com.loopers.application.ranking;

import com.loopers.zset.RedisZSetTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * 랭킹 점수 계산 및 ZSET 적재 서비스.
 * <p>
 * Kafka Consumer에서 이벤트를 수취하여 Redis ZSET에 랭킹 점수를 적재합니다.
 * </p>
 * <p>
 * <b>설계 원칙:</b>
 * <ul>
 *   <li>Application 유즈케이스: Ranking은 도메인이 아닌 파생 View로 취급</li>
 *   <li>Eventually Consistent: 일시적인 지연/중복 허용</li>
 *   <li>CQRS Read Model: Write Side(도메인) → Kafka → Read Side(Application) → Redis ZSET</li>
 *   <li>단순성: ZSetTemplate을 직접 사용하여 불필요한 추상화 제거</li>
 * </ul>
 * </p>
 * <p>
 * <b>점수 계산 공식:</b>
 * <ul>
 *   <li>조회: Weight = 0.1, Score = 1</li>
 *   <li>좋아요: Weight = 0.2, Score = 1</li>
 *   <li>주문: Weight = 0.6, Score = price * amount (정규화: log(1 + amount))</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {
    private static final double VIEW_WEIGHT = 0.1;
    private static final double LIKE_WEIGHT = 0.2;
    private static final double ORDER_WEIGHT = 0.6;
    private static final Duration TTL = Duration.ofDays(2);

    private final RedisZSetTemplate zSetTemplate;
    private final RankingKeyGenerator keyGenerator;

    /**
     * 조회 이벤트 점수를 ZSET에 추가합니다.
     *
     * @param productId 상품 ID
     * @param date 날짜
     */
    public void addViewScore(Long productId, LocalDate date) {
        String key = keyGenerator.generateDailyKey(date);
        double score = VIEW_WEIGHT;
        incrementScore(key, productId, score);
        log.debug("조회 점수 추가: productId={}, date={}, score={}", productId, date, score);
    }

    /**
     * 좋아요 이벤트 점수를 ZSET에 추가/차감합니다.
     *
     * @param productId 상품 ID
     * @param date 날짜
     * @param isAdded 좋아요 추가 여부 (true: 추가, false: 취소)
     */
    public void addLikeScore(Long productId, LocalDate date, boolean isAdded) {
        String key = keyGenerator.generateDailyKey(date);
        double score = isAdded ? LIKE_WEIGHT : -LIKE_WEIGHT;
        incrementScore(key, productId, score);
        log.debug("좋아요 점수 {}: productId={}, date={}, score={}", 
            isAdded ? "추가" : "차감", productId, date, score);
    }

    /**
     * 주문 이벤트 점수를 ZSET에 추가합니다.
     * <p>
     * 주문 금액을 기반으로 점수를 계산합니다.
     * 정규화를 위해 log(1 + orderAmount)를 사용합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param date 날짜
     * @param orderAmount 주문 금액 (price * quantity)
     */
    public void addOrderScore(Long productId, LocalDate date, double orderAmount) {
        String key = keyGenerator.generateDailyKey(date);
        // 정규화: log(1 + orderAmount) 사용하여 큰 금액 차이를 완화
        double score = Math.log1p(orderAmount) * ORDER_WEIGHT;
        incrementScore(key, productId, score);
        log.debug("주문 점수 추가: productId={}, date={}, orderAmount={}, score={}", 
            productId, date, orderAmount, score);
    }

    /**
     * 배치로 점수를 적재합니다.
     * <p>
     * 같은 배치 내에서 같은 상품의 여러 이벤트를 메모리에서 집계한 후 한 번에 적재합니다.
     * </p>
     *
     * @param scoreMap 상품 ID별 점수 맵
     * @param date 날짜
     */
    public void addScoresBatch(Map<Long, Double> scoreMap, LocalDate date) {
        if (scoreMap.isEmpty()) {
            return;
        }
        
        String key = keyGenerator.generateDailyKey(date);
        for (Map.Entry<Long, Double> entry : scoreMap.entrySet()) {
            zSetTemplate.incrementScore(key, String.valueOf(entry.getKey()), entry.getValue());
        }
        
        // TTL 설정 (최초 1회만)
        zSetTemplate.setTtlIfNotExists(key, TTL);
        
        log.debug("배치 점수 적재 완료: date={}, count={}", date, scoreMap.size());
    }

    /**
     * ZSET에 점수를 증가시킵니다.
     * <p>
     * 점수 계산 후 ZSetTemplate을 통해 Redis에 적재합니다.
     * </p>
     *
     * @param key ZSET 키
     * @param productId 상품 ID
     * @param score 증가시킬 점수
     */
    private void incrementScore(String key, Long productId, double score) {
        zSetTemplate.incrementScore(key, String.valueOf(productId), score);
        // TTL 설정 (최초 1회만)
        zSetTemplate.setTtlIfNotExists(key, TTL);
    }
}
