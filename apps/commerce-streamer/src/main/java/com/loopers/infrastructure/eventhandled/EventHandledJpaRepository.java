package com.loopers.infrastructure.eventhandled;

import com.loopers.domain.eventhandled.EventHandled;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * EventHandled 엔티티에 대한 JPA Repository.
 *
 * @author Loopers
 * @version 1.0
 */
public interface EventHandledJpaRepository extends JpaRepository<EventHandled, String> {

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
