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

    /**
     * 집계 ID와 집계 타입으로 최신 버전을 조회합니다.
     *
     * @param aggregateId 집계 ID (예: productId, orderId)
     * @param aggregateType 집계 타입 (예: "Product", "Order")
     * @return 최신 버전 (없으면 0L)
     */
    @Query("SELECT COALESCE(MAX(e.version), 0L) FROM OutboxEvent e " +
           "WHERE e.aggregateId = :aggregateId AND e.aggregateType = :aggregateType")
    Long findLatestVersionByAggregateId(
        @Param("aggregateId") String aggregateId,
        @Param("aggregateType") String aggregateType
    );
}
