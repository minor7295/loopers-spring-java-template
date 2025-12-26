package com.loopers.application.ranking;

import com.loopers.zset.RedisZSetTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RankingService 테스트.
 */
@ExtendWith(MockitoExtension.class)
class RankingServiceTest {

    @Mock
    private RedisZSetTemplate zSetTemplate;

    @Mock
    private RankingKeyGenerator keyGenerator;

    @InjectMocks
    private RankingService rankingService;

    @DisplayName("조회 점수를 ZSET에 추가할 수 있다.")
    @Test
    void canAddViewScore() {
        // arrange
        Long productId = 1L;
        LocalDate date = LocalDate.of(2024, 12, 15);
        String expectedKey = "ranking:all:20241215";
        double expectedScore = 0.1; // VIEW_WEIGHT

        when(keyGenerator.generateDailyKey(date)).thenReturn(expectedKey);

        // act
        rankingService.addViewScore(productId, date);

        // assert
        verify(keyGenerator).generateDailyKey(date);
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq(String.valueOf(productId)), eq(expectedScore));
        verify(zSetTemplate).setTtlIfNotExists(eq(expectedKey), eq(Duration.ofDays(2)));
    }

    @DisplayName("좋아요 추가 시 점수를 ZSET에 추가할 수 있다.")
    @Test
    void canAddLikeScore_whenAdded() {
        // arrange
        Long productId = 1L;
        LocalDate date = LocalDate.of(2024, 12, 15);
        String expectedKey = "ranking:all:20241215";
        double expectedScore = 0.2; // LIKE_WEIGHT
        boolean isAdded = true;

        when(keyGenerator.generateDailyKey(date)).thenReturn(expectedKey);

        // act
        rankingService.addLikeScore(productId, date, isAdded);

        // assert
        verify(keyGenerator).generateDailyKey(date);
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq(String.valueOf(productId)), eq(expectedScore));
        verify(zSetTemplate).setTtlIfNotExists(eq(expectedKey), eq(Duration.ofDays(2)));
    }

    @DisplayName("좋아요 취소 시 점수를 ZSET에서 차감할 수 있다.")
    @Test
    void canSubtractLikeScore_whenRemoved() {
        // arrange
        Long productId = 1L;
        LocalDate date = LocalDate.of(2024, 12, 15);
        String expectedKey = "ranking:all:20241215";
        double expectedScore = -0.2; // -LIKE_WEIGHT
        boolean isAdded = false;

        when(keyGenerator.generateDailyKey(date)).thenReturn(expectedKey);

        // act
        rankingService.addLikeScore(productId, date, isAdded);

        // assert
        verify(keyGenerator).generateDailyKey(date);
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq(String.valueOf(productId)), eq(expectedScore));
        verify(zSetTemplate).setTtlIfNotExists(eq(expectedKey), eq(Duration.ofDays(2)));
    }

    @DisplayName("주문 점수를 ZSET에 추가할 수 있다.")
    @Test
    void canAddOrderScore() {
        // arrange
        Long productId = 1L;
        LocalDate date = LocalDate.of(2024, 12, 15);
        String expectedKey = "ranking:all:20241215";
        double orderAmount = 10000.0;
        // 정규화: log(1 + orderAmount) * ORDER_WEIGHT
        // log(1 + 10000) ≈ 9.2103, 9.2103 * 0.6 ≈ 5.526
        double expectedScore = Math.log1p(orderAmount) * 0.6; // ORDER_WEIGHT = 0.6

        when(keyGenerator.generateDailyKey(date)).thenReturn(expectedKey);

        // act
        rankingService.addOrderScore(productId, date, orderAmount);

        // assert
        verify(keyGenerator).generateDailyKey(date);
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq(String.valueOf(productId)), eq(expectedScore));
        verify(zSetTemplate).setTtlIfNotExists(eq(expectedKey), eq(Duration.ofDays(2)));
    }

    @DisplayName("주문 금액이 0일 때도 정상적으로 처리된다.")
    @Test
    void canAddOrderScore_whenOrderAmountIsZero() {
        // arrange
        Long productId = 1L;
        LocalDate date = LocalDate.of(2024, 12, 15);
        String expectedKey = "ranking:all:20241215";
        double orderAmount = 0.0;
        double expectedScore = Math.log1p(orderAmount) * 0.6; // log(1) * 0.6 = 0

        when(keyGenerator.generateDailyKey(date)).thenReturn(expectedKey);

        // act
        rankingService.addOrderScore(productId, date, orderAmount);

        // assert
        verify(keyGenerator).generateDailyKey(date);
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq(String.valueOf(productId)), eq(expectedScore));
        verify(zSetTemplate).setTtlIfNotExists(eq(expectedKey), eq(Duration.ofDays(2)));
    }

    @DisplayName("배치로 여러 상품의 점수를 한 번에 적재할 수 있다.")
    @Test
    void canAddScoresBatch() {
        // arrange
        LocalDate date = LocalDate.of(2024, 12, 15);
        String expectedKey = "ranking:all:20241215";
        
        Map<Long, Double> scoreMap = new HashMap<>();
        scoreMap.put(1L, 10.5);
        scoreMap.put(2L, 20.3);
        scoreMap.put(3L, 15.7);

        when(keyGenerator.generateDailyKey(date)).thenReturn(expectedKey);

        // act
        rankingService.addScoresBatch(scoreMap, date);

        // assert
        verify(keyGenerator).generateDailyKey(date);
        
        // 각 상품에 대해 incrementScore 호출 확인
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq("1"), eq(10.5));
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq("2"), eq(20.3));
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq("3"), eq(15.7));
        
        // TTL 설정은 한 번만 호출
        verify(zSetTemplate, times(1)).setTtlIfNotExists(eq(expectedKey), eq(Duration.ofDays(2)));
    }

    @DisplayName("빈 맵을 배치로 적재할 때는 아무 작업도 수행하지 않는다.")
    @Test
    void doesNothing_whenBatchIsEmpty() {
        // arrange
        LocalDate date = LocalDate.of(2024, 12, 15);
        Map<Long, Double> emptyScoreMap = new HashMap<>();

        // act
        rankingService.addScoresBatch(emptyScoreMap, date);

        // assert
        verify(keyGenerator, never()).generateDailyKey(any());
        verify(zSetTemplate, never()).incrementScore(anyString(), anyString(), anyDouble());
        verify(zSetTemplate, never()).setTtlIfNotExists(anyString(), any(Duration.class));
    }

    @DisplayName("여러 날짜에 대해 독립적으로 점수를 추가할 수 있다.")
    @Test
    void canAddScoresForDifferentDates() {
        // arrange
        Long productId = 1L;
        LocalDate date1 = LocalDate.of(2024, 12, 15);
        LocalDate date2 = LocalDate.of(2024, 12, 16);
        String key1 = "ranking:all:20241215";
        String key2 = "ranking:all:20241216";

        when(keyGenerator.generateDailyKey(date1)).thenReturn(key1);
        when(keyGenerator.generateDailyKey(date2)).thenReturn(key2);

        // act
        rankingService.addViewScore(productId, date1);
        rankingService.addViewScore(productId, date2);

        // assert
        verify(keyGenerator).generateDailyKey(date1);
        verify(keyGenerator).generateDailyKey(date2);
        verify(zSetTemplate).incrementScore(eq(key1), eq(String.valueOf(productId)), eq(0.1));
        verify(zSetTemplate).incrementScore(eq(key2), eq(String.valueOf(productId)), eq(0.1));
        verify(zSetTemplate).setTtlIfNotExists(eq(key1), eq(Duration.ofDays(2)));
        verify(zSetTemplate).setTtlIfNotExists(eq(key2), eq(Duration.ofDays(2)));
    }

    @DisplayName("같은 상품에 여러 이벤트를 추가하면 점수가 누적된다.")
    @Test
    void accumulatesScoresForSameProduct() {
        // arrange
        Long productId = 1L;
        LocalDate date = LocalDate.of(2024, 12, 15);
        String expectedKey = "ranking:all:20241215";

        when(keyGenerator.generateDailyKey(date)).thenReturn(expectedKey);

        // act
        rankingService.addViewScore(productId, date);      // +0.1
        rankingService.addLikeScore(productId, date, true); // +0.2
        rankingService.addOrderScore(productId, date, 1000.0); // +log(1001) * 0.6

        // assert
        verify(keyGenerator, times(3)).generateDailyKey(date);
        
        // 각 이벤트별로 incrementScore 호출 확인
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq(String.valueOf(productId)), eq(0.1));
        verify(zSetTemplate).incrementScore(eq(expectedKey), eq(String.valueOf(productId)), eq(0.2));
        
        ArgumentCaptor<Double> scoreCaptor = ArgumentCaptor.forClass(Double.class);
        verify(zSetTemplate, times(3)).incrementScore(eq(expectedKey), eq(String.valueOf(productId)), scoreCaptor.capture());
        
        // 주문 점수 계산 확인
        double orderScore = scoreCaptor.getAllValues().get(2);
        double expectedOrderScore = Math.log1p(1000.0) * 0.6;
        assertThat(orderScore).isCloseTo(expectedOrderScore, org.assertj.core.data.Offset.offset(0.001));
        
        // TTL 설정은 각 호출마다 수행됨 (incrementScore 내부에서 호출)
        verify(zSetTemplate, times(3)).setTtlIfNotExists(eq(expectedKey), eq(Duration.ofDays(2)));
    }

    @DisplayName("Score Carry-Over로 오늘 랭킹을 내일 랭킹에 반영할 수 있다.")
    @Test
    void canCarryOverScore() {
        // arrange
        LocalDate today = LocalDate.of(2024, 12, 15);
        LocalDate tomorrow = LocalDate.of(2024, 12, 16);
        String todayKey = "ranking:all:20241215";
        String tomorrowKey = "ranking:all:20241216";
        double carryOverWeight = 0.1; // 10%

        when(keyGenerator.generateDailyKey(today)).thenReturn(todayKey);
        when(keyGenerator.generateDailyKey(tomorrow)).thenReturn(tomorrowKey);
        when(zSetTemplate.unionStoreWithWeight(eq(tomorrowKey), eq(todayKey), eq(carryOverWeight)))
            .thenReturn(50L);

        // act
        Long result = rankingService.carryOverScore(today, tomorrow, carryOverWeight);

        // assert
        assertThat(result).isEqualTo(50L);
        verify(keyGenerator).generateDailyKey(today);
        verify(keyGenerator).generateDailyKey(tomorrow);
        verify(zSetTemplate).unionStoreWithWeight(eq(tomorrowKey), eq(todayKey), eq(carryOverWeight));
        verify(zSetTemplate).setTtlIfNotExists(eq(tomorrowKey), eq(Duration.ofDays(2)));
    }

    @DisplayName("Score Carry-Over 가중치가 0일 때도 정상적으로 처리된다.")
    @Test
    void canCarryOverScore_withZeroWeight() {
        // arrange
        LocalDate today = LocalDate.of(2024, 12, 15);
        LocalDate tomorrow = LocalDate.of(2024, 12, 16);
        String todayKey = "ranking:all:20241215";
        String tomorrowKey = "ranking:all:20241216";
        double carryOverWeight = 0.0;

        when(keyGenerator.generateDailyKey(today)).thenReturn(todayKey);
        when(keyGenerator.generateDailyKey(tomorrow)).thenReturn(tomorrowKey);
        when(zSetTemplate.unionStoreWithWeight(eq(tomorrowKey), eq(todayKey), eq(carryOverWeight)))
            .thenReturn(0L);

        // act
        Long result = rankingService.carryOverScore(today, tomorrow, carryOverWeight);

        // assert
        assertThat(result).isEqualTo(0L);
        verify(zSetTemplate).unionStoreWithWeight(eq(tomorrowKey), eq(todayKey), eq(carryOverWeight));
    }
}
