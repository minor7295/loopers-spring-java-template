package com.loopers.infrastructure.scheduler;

import com.loopers.application.ranking.RankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 랭킹 Score Carry-Over 스케줄러.
 * <p>
 * 매일 자정에 전날 랭킹을 오늘 랭킹에 일부 반영하여 콜드 스타트 문제를 완화합니다.
 * </p>
 * <p>
 * <b>설계 원칙:</b>
 * <ul>
 *   <li>콜드 스타트 완화: 매일 자정에 랭킹이 0점에서 시작하는 문제를 완화</li>
 *   <li>가중치 적용: 전날 랭킹의 일부(예: 10%)만 반영하여 신선도 유지</li>
 *   <li>에러 처리: Carry-Over 실패 시에도 다음 스케줄에서 재시도</li>
 * </ul>
 * </p>
 * <p>
 * <b>실행 시점:</b>
 * <ul>
 *   <li>매일 자정(00:00:00)에 실행</li>
 *   <li>전날(어제) 랭킹을 오늘 랭킹에 반영</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingCarryOverScheduler {

    private static final double DEFAULT_CARRY_OVER_WEIGHT = 0.1; // 10%

    private final RankingService rankingService;

    /**
     * 전날 랭킹을 오늘 랭킹에 일부 반영합니다.
     * <p>
     * 매일 자정에 실행되어 어제 랭킹의 일부를 오늘 랭킹에 반영합니다.
     * </p>
     */
    @Scheduled(cron = "0 0 0 * * ?") // 매일 자정 (00:00:00)
    public void carryOverScore() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        LocalDate yesterday = today.minusDays(1);

        try {
            Long memberCount = rankingService.carryOverScore(yesterday, today, DEFAULT_CARRY_OVER_WEIGHT);

            log.info("랭킹 Score Carry-Over 완료: yesterday={}, today={}, weight={}, memberCount={}",
                yesterday, today, DEFAULT_CARRY_OVER_WEIGHT, memberCount);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Redis 장애로 인한 랭킹 Score Carry-Over 실패: yesterday={}, today={}, error={}",
                yesterday, today, e.getMessage());
            // Redis 장애 시 Carry-Over 스킵 (다음 스케줄에서 재시도)
        } catch (Exception e) {
            log.warn("랭킹 Score Carry-Over 실패: yesterday={}, today={}", yesterday, today, e);
            // Carry-Over 실패는 다음 스케줄에서 재시도
        }
    }
}

