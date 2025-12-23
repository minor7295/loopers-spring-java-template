package com.loopers.zset;

/**
 * ZSET 엔트리 (멤버와 점수 쌍).
 *
 * @param member 멤버
 * @param score 점수
 * @author Loopers
 * @version 1.0
 */
public record ZSetEntry(String member, Double score) {
}
