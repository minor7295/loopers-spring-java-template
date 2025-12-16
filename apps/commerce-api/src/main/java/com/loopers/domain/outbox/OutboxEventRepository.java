package com.loopers.domain.outbox;

import java.util.List;

/**
 * OutboxEvent 저장소 인터페이스.
 * <p>
 * DIP를 준수하여 도메인 레이어에서 인터페이스를 정의합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface OutboxEventRepository {

    /**
     * Outbox 이벤트를 저장합니다.
     *
     * @param outboxEvent 저장할 Outbox 이벤트
     * @return 저장된 Outbox 이벤트
     */
    OutboxEvent save(OutboxEvent outboxEvent);

    /**
     * 발행 대기 중인 이벤트 목록을 조회합니다.
     *
     * @param limit 조회할 최대 개수
     * @return 발행 대기 중인 이벤트 목록
     */
    List<OutboxEvent> findPendingEvents(int limit);

    /**
     * ID로 Outbox 이벤트를 조회합니다.
     *
     * @param id Outbox 이벤트 ID
     * @return 조회된 Outbox 이벤트
     */
    OutboxEvent findById(Long id);
}
