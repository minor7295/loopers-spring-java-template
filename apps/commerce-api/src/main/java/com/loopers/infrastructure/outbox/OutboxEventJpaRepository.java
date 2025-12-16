package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * OutboxEvent JPA Repository.
 */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 발행 대기 중인 이벤트 목록을 생성 시간 순으로 조회합니다.
     *
     * @param limit 조회할 최대 개수
     * @return 발행 대기 중인 이벤트 목록
     */
    @Query(value = "SELECT * FROM outbox_event e " +
           "WHERE e.status = 'PENDING' " +
           "ORDER BY e.created_at ASC " +
           "LIMIT :limit", nativeQuery = true)
    List<OutboxEvent> findPendingEvents(@Param("limit") int limit);
}
