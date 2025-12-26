package com.loopers.application.ranking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 랭킹 스냅샷 서비스.
 * <p>
 * Redis 장애 시 Fallback으로 사용하기 위한 랭킹 데이터 스냅샷을 인메모리에 저장합니다.
 * </p>
 * <p>
 * <b>설계 원칙:</b>
 * <ul>
 *   <li>인메모리 캐시: 구현이 간단하고 성능이 우수함</li>
 *   <li>메모리 관리: 최근 7일치만 보관하여 메모리 사용량 제한</li>
 *   <li>스냅샷 기반 Fallback: DB 실시간 재계산 대신 스냅샷 서빙으로 DB 부하 방지</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Service
public class RankingSnapshotService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_SNAPSHOTS = 7; // 최근 7일치만 보관

    private final Map<String, RankingService.RankingsResponse> snapshotCache = new ConcurrentHashMap<>();

    /**
     * 랭킹 스냅샷을 저장합니다.
     *
     * @param date 날짜
     * @param rankings 랭킹 조회 결과
     */
    public void saveSnapshot(LocalDate date, RankingService.RankingsResponse rankings) {
        String key = date.format(DATE_FORMATTER);
        snapshotCache.put(key, rankings);
        log.debug("랭킹 스냅샷 저장: date={}, key={}, itemCount={}", date, key, rankings.items().size());

        // 오래된 스냅샷 정리 (메모리 관리)
        cleanupOldSnapshots();
    }

    /**
     * 랭킹 스냅샷을 조회합니다.
     *
     * @param date 날짜
     * @return 랭킹 조회 결과 (없으면 empty)
     */
    public Optional<RankingService.RankingsResponse> getSnapshot(LocalDate date) {
        String key = date.format(DATE_FORMATTER);
        RankingService.RankingsResponse snapshot = snapshotCache.get(key);
        
        if (snapshot != null) {
            log.debug("랭킹 스냅샷 조회 성공: date={}, key={}, itemCount={}", date, key, snapshot.items().size());
            return Optional.of(snapshot);
        }
        
        log.debug("랭킹 스냅샷 없음: date={}, key={}", date, key);
        return Optional.empty();
    }

    /**
     * 오래된 스냅샷을 정리합니다.
     * <p>
     * 최근 7일치만 보관하여 메모리 사용량을 제한합니다.
     * </p>
     */
    private void cleanupOldSnapshots() {
        if (snapshotCache.size() <= MAX_SNAPSHOTS) {
            return;
        }

        // 가장 오래된 스냅샷 제거
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        LocalDate oldestDate = today.minusDays(MAX_SNAPSHOTS);
        
        snapshotCache.entrySet().removeIf(entry -> {
            try {
                LocalDate entryDate = LocalDate.parse(entry.getKey(), DATE_FORMATTER);
                boolean shouldRemove = entryDate.isBefore(oldestDate);
                if (shouldRemove) {
                    log.debug("오래된 스냅샷 제거: key={}", entry.getKey());
                }
                return shouldRemove;
            } catch (Exception e) {
                log.warn("스냅샷 키 파싱 실패, 제거: key={}", entry.getKey(), e);
                return true; // 파싱 실패한 키는 제거
            }
        });
    }
}

