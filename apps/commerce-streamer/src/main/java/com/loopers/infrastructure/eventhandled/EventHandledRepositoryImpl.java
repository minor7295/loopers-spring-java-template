package com.loopers.infrastructure.eventhandled;

import com.loopers.domain.eventhandled.EventHandled;
import com.loopers.domain.eventhandled.EventHandledRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * EventHandledRepository의 구현체.
 * <p>
 * JPA를 사용하여 EventHandled 엔티티의 영속성을 관리합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
public class EventHandledRepositoryImpl implements EventHandledRepository {

    private final EventHandledJpaRepository jpaRepository;

    @Override
    public EventHandled save(EventHandled eventHandled) {
        return jpaRepository.save(eventHandled);
    }

    @Override
    public Optional<EventHandled> findByEventId(String eventId) {
        return jpaRepository.findByEventId(eventId);
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return jpaRepository.existsByEventId(eventId);
    }
}
