package com.loopers.zset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis ZSET 템플릿.
 * <p>
 * Redis Sorted Set (ZSET) 조작 기능을 제공합니다.
 * ZSET은 Redis 전용 데이터 구조이므로 인터페이스 분리 없이 클래스로 직접 제공합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisZSetTemplate {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * ZSET에 점수를 증가시킵니다.
     * <p>
     * ZINCRBY는 원자적 연산이므로 동시성 문제가 없습니다.
     * </p>
     *
     * @param key ZSET 키
     * @param member 멤버 (예: 상품 ID)
     * @param score 증가시킬 점수
     */
    public void incrementScore(String key, String member, double score) {
        try {
            redisTemplate.opsForZSet().incrementScore(key, member, score);
        } catch (Exception e) {
            log.warn("ZSET 점수 증가 실패: key={}, member={}, score={}", key, member, score, e);
            // Redis 연결 실패 시 로그만 기록하고 계속 진행
        }
    }

    /**
     * ZSET의 TTL을 설정합니다.
     * <p>
     * 이미 TTL이 설정되어 있으면 설정하지 않습니다.
     * </p>
     *
     * @param key ZSET 키
     * @param ttl TTL (Duration)
     */
    public void setTtlIfNotExists(String key, Duration ttl) {
        try {
            Long currentTtl = redisTemplate.getExpire(key);
            if (currentTtl == null || currentTtl == -1) {
                // TTL이 없거나 -1(만료 시간 없음)인 경우에만 설정
                redisTemplate.expire(key, ttl);
            }
        } catch (Exception e) {
            log.warn("ZSET TTL 설정 실패: key={}", key, e);
        }
    }

    /**
     * 특정 멤버의 순위를 조회합니다.
     * <p>
     * 점수가 높은 순서대로 정렬된 순위를 반환합니다 (0부터 시작).
     * 멤버가 없으면 null을 반환합니다.
     * </p>
     *
     * @param key ZSET 키
     * @param member 멤버
     * @return 순위 (0부터 시작, 없으면 null)
     */
    public Long getRank(String key, String member) {
        try {
            return redisTemplate.opsForZSet().reverseRank(key, member);
        } catch (Exception e) {
            log.warn("ZSET 순위 조회 실패: key={}, member={}", key, member, e);
            return null;
        }
    }

    /**
     * ZSET에서 상위 N개 멤버를 조회합니다.
     * <p>
     * 점수가 높은 순서대로 정렬된 멤버와 점수를 반환합니다.
     * </p>
     *
     * @param key ZSET 키
     * @param start 시작 인덱스 (0부터 시작)
     * @param end 종료 인덱스 (포함)
     * @return 멤버와 점수 쌍의 리스트
     */
    public List<ZSetEntry> getTopRankings(String key, long start, long end) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key, start, end);
            
            if (tuples == null) {
                return List.of();
            }
            
            List<ZSetEntry> entries = new ArrayList<>();
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                entries.add(new ZSetEntry(tuple.getValue(), tuple.getScore()));
            }
            return entries;
        } catch (Exception e) {
            log.warn("ZSET 상위 랭킹 조회 실패: key={}, start={}, end={}", key, start, end, e);
            return List.of();
        }
    }

    /**
     * ZSET의 크기를 조회합니다.
     * <p>
     * ZSET에 포함된 멤버의 총 개수를 반환합니다.
     * </p>
     *
     * @param key ZSET 키
     * @return ZSET 크기 (없으면 0)
     */
    public Long getSize(String key) {
        try {
            Long size = redisTemplate.opsForZSet().size(key);
            return size != null ? size : 0L;
        } catch (Exception e) {
            log.warn("ZSET 크기 조회 실패: key={}", key, e);
            return 0L;
        }
    }

    /**
     * 여러 ZSET을 합쳐서 새로운 ZSET을 생성합니다.
     * <p>
     * ZUNIONSTORE 명령어를 사용하여 여러 소스 ZSET의 점수를 합산합니다.
     * 같은 멤버가 여러 ZSET에 있으면 점수가 합산됩니다.
     * </p>
     * <p>
     * <b>사용 사례:</b>
     * <ul>
     *   <li>시간 단위 랭킹을 일간 랭킹으로 집계</li>
     *   <li>Score Carry-Over: 오늘 랭킹을 내일 랭킹에 일부 반영</li>
     * </ul>
     * </p>
     *
     * @param destination 목적지 ZSET 키
     * @param sourceKeys 소스 ZSET 키 목록
     * @return 합쳐진 ZSET의 멤버 수
     */
    public Long unionStore(String destination, List<String> sourceKeys) {
        try {
            if (sourceKeys.isEmpty()) {
                log.warn("소스 키가 비어있습니다: destination={}", destination);
                return 0L;
            }

            Long result = redisTemplate.opsForZSet().unionAndStore(
                sourceKeys.get(0),
                sourceKeys.subList(1, sourceKeys.size()),
                destination
            );
            return result != null ? result : 0L;
        } catch (Exception e) {
            log.warn("ZSET 합치기 실패: destination={}, sourceKeys={}", destination, sourceKeys, e);
            return 0L;
        }
    }

    /**
     * 단일 ZSET을 가중치를 적용하여 목적지 ZSET에 합산합니다.
     * <p>
     * 소스 ZSET의 점수에 가중치를 곱한 후 목적지 ZSET에 합산합니다.
     * 목적지 ZSET이 이미 존재하면 기존 점수에 합산됩니다.
     * </p>
     * <p>
     * <b>사용 사례:</b>
     * <ul>
     *   <li>Score Carry-Over: 오늘 랭킹을 0.1 배율로 내일 랭킹에 반영</li>
     * </ul>
     * </p>
     *
     * @param destination 목적지 ZSET 키
     * @param sourceKey 소스 ZSET 키
     * @param weight 가중치 (예: 0.1 = 10%)
     * @return 합쳐진 ZSET의 멤버 수
     */
    public Long unionStoreWithWeight(String destination, String sourceKey, double weight) {
        try {
            // ZUNIONSTORE를 사용하여 가중치 적용
            // destination과 sourceKey를 합치되, sourceKey에만 가중치 적용
            // 이를 위해 임시 키를 사용하거나 직접 구현
            
            // 방법: sourceKey의 모든 멤버를 읽어서 가중치를 적용한 후 destination에 추가
            Set<ZSetOperations.TypedTuple<String>> sourceMembers = redisTemplate.opsForZSet()
                .rangeWithScores(sourceKey, 0, -1);
            
            if (sourceMembers == null || sourceMembers.isEmpty()) {
                return 0L;
            }
            
            // 가중치를 적용하여 destination에 추가
            for (ZSetOperations.TypedTuple<String> tuple : sourceMembers) {
                String member = tuple.getValue();
                Double originalScore = tuple.getScore();
                if (member != null && originalScore != null) {
                    double weightedScore = originalScore * weight;
                    redisTemplate.opsForZSet().incrementScore(destination, member, weightedScore);
                }
            }
            
            return (long) sourceMembers.size();
        } catch (Exception e) {
            log.warn("ZSET 가중치 합치기 실패: destination={}, sourceKey={}, weight={}", 
                destination, sourceKey, weight, e);
            return 0L;
        }
    }
}
