package com.loopers.domain.eventhandled;

import java.util.Optional;

/**
 * EventHandled 엔티티에 대한 저장소 인터페이스.
 * <p>
 * 이벤트 처리 기록의 영속성 계층과의 상호작용을 정의합니다.
 * DIP를 준수하여 도메인 레이어에서 인터페이스를 정의합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface EventHandledRepository {

    /**
     * 이벤트 처리 기록을 저장합니다.
     *
     * @param eventHandled 저장할 이벤트 처리 기록
     * @return 저장된 이벤트 처리 기록
     */
    EventHandled save(EventHandled eventHandled);

    /**
     * 이벤트 ID로 처리 기록을 조회합니다.
     *
     * @param eventId 이벤트 ID
     * @return 조회된 처리 기록을 담은 Optional
     */
    Optional<EventHandled> findByEventId(String eventId);

    /**
     * 이벤트 ID가 이미 처리되었는지 확인합니다.
     *
     * @param eventId 이벤트 ID
     * @return 이미 처리된 경우 true, 그렇지 않으면 false
     */
    boolean existsByEventId(String eventId);
}
