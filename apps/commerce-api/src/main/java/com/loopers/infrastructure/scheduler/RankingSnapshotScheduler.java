package com.loopers.infrastructure.scheduler;

import com.loopers.application.ranking.RankingService;
import com.loopers.application.ranking.RankingSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 랭킹 스냅샷 저장 스케줄러.
 * <p>
 * 주기적으로 랭킹 결과를 스냅샷으로 저장하여, Redis 장애 시 Fallback으로 사용할 수 있도록 합니다.
 * </p>
 * <p>
 * <b>설계 원칙:</b>
 * <ul>
 *   <li>스냅샷 기반 Fallback: DB 실시간 재계산 대신 스냅샷 서빙으로 DB 부하 방지</li>
 *   <li>주기적 저장: 1시간마다 최신 랭킹을 스냅샷으로 저장</li>
 *   <li>에러 처리: 스냅샷 저장 실패 시에도 다음 스케줄에서 재시도</li>
 * </ul>
 * </p>
 * <p>
 * <b>주기 선택 근거:</b>
 * <ul>
 *   <li>비용 대비 효과: 1시간 주기가 리소스 사용량이 1/12로 감소하면서도 사용자 체감 차이는 거의 없음</li>
 *   <li>랭킹의 성격: 비즈니스 결정이 아닌 조회용 파생 데이터이므로 1시간 전 데이터도 충분히 유용함</li>
 *   <li>운영 관점: 스케줄러 실행 빈도가 낮아 모니터링 부담 감소</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingSnapshotScheduler {

    private final RankingService rankingService;
    private final RankingSnapshotService rankingSnapshotService;

    /**
     * 랭킹 스냅샷을 저장합니다.
     * <p>
     * 1시간마다 실행되어 오늘의 랭킹을 스냅샷으로 저장합니다.
     * </p>
     */
    @Scheduled(fixedRate = 3600000) // 1시간마다 (3600000ms = 1시간)
    public void saveRankingSnapshot() {
        LocalDate today = LocalDate.now();
        try {
            // 상위 100개 랭킹을 스냅샷으로 저장 (대부분의 사용자가 상위 100개 이내만 조회)
            // Redis가 정상일 때만 스냅샷 저장 (예외 발생 시 스킵)
            RankingService.RankingsResponse rankings = rankingService.getRankingsFromRedis(today, 0, 100);
            
            rankingSnapshotService.saveSnapshot(today, rankings);
            
            log.debug("랭킹 스냅샷 저장 완료: date={}, itemCount={}", today, rankings.items().size());
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Redis 장애로 인한 랭킹 스냅샷 저장 실패: date={}, error={}", today, e.getMessage());
            // Redis 장애 시 스냅샷 저장 스킵 (다음 스케줄에서 재시도)
        } catch (Exception e) {
            log.warn("랭킹 스냅샷 저장 실패: date={}", today, e);
            // 스냅샷 저장 실패는 다음 스케줄에서 재시도
        }
    }
}

