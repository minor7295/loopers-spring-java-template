package com.loopers.application.ranking;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 랭킹 키 생성 유틸리티.
 * <p>
 * Redis ZSET 랭킹 키를 생성합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Component
public class RankingKeyGenerator {
    private static final String DAILY_KEY_PREFIX = "ranking:all:";
    private static final String HOURLY_KEY_PREFIX = "ranking:hourly:";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHH");

    /**
     * 일간 랭킹 키를 생성합니다.
     * <p>
     * 예: ranking:all:20241215
     * </p>
     *
     * @param date 날짜
     * @return 일간 랭킹 키
     */
    public String generateDailyKey(LocalDate date) {
        String dateStr = date.format(DATE_FORMATTER);
        return DAILY_KEY_PREFIX + dateStr;
    }

    /**
     * 시간 단위 랭킹 키를 생성합니다.
     * <p>
     * 예: ranking:hourly:2024121514
     * </p>
     *
     * @param dateTime 날짜 및 시간
     * @return 시간 단위 랭킹 키
     */
    public String generateHourlyKey(LocalDateTime dateTime) {
        String dateTimeStr = dateTime.format(DATE_TIME_FORMATTER);
        return HOURLY_KEY_PREFIX + dateTimeStr;
    }
}
