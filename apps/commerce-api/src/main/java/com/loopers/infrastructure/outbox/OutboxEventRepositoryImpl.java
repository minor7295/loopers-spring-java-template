package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OutboxEventRepository의 JPA 구현체.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventRepositoryImpl implements OutboxEventRepository {

    private final OutboxEventJpaRepository outboxEventJpaRepository;

    @Override
    public OutboxEvent save(OutboxEvent outboxEvent) {
        return outboxEventJpaRepository.save(outboxEvent);
    }

    @Override
    public List<OutboxEvent> findPendingEvents(int limit) {
        return outboxEventJpaRepository.findPendingEvents(limit);
    }

    @Override
    public OutboxEvent findById(Long id) {
        return outboxEventJpaRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("OutboxEvent not found: " + id));
    }

    @Override
    public Long findLatestVersionByAggregateId(String aggregateId, String aggregateType) {
        return outboxEventJpaRepository.findLatestVersionByAggregateId(aggregateId, aggregateType);
    }
}
