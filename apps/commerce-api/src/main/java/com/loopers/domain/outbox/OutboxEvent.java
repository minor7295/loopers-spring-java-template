package com.loopers.domain.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox 이벤트 엔티티.
 * <p>
 * Transactional Outbox Pattern을 구현하기 위한 엔티티입니다.
 * 도메인 트랜잭션과 같은 트랜잭션에서 이벤트를 저장하고,
 * 별도 프로세스가 이를 읽어 Kafka로 발행합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Entity
@Table(name = "outbox_event", 
    indexes = {
        @Index(name = "idx_status_created", columnList = "status, created_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_aggregate_version",
            columnNames = {"aggregate_id", "aggregate_type", "version"}
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 255)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    @Column(name = "partition_key", length = 255)
    private String partitionKey;

    @Column(name = "version")
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Builder
    public OutboxEvent(
        String eventId,
        String eventType,
        String aggregateId,
        String aggregateType,
        String payload,
        String topic,
        String partitionKey,
        Long version
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.payload = payload;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.version = version;
        this.status = OutboxStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 이벤트를 발행 완료 상태로 변경합니다.
     */
    public void markAsPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * 이벤트를 실패 상태로 변경합니다.
     */
    public void markAsFailed() {
        this.status = OutboxStatus.FAILED;
    }

    /**
     * Outbox 이벤트 상태.
     */
    public enum OutboxStatus {
        PENDING,    // 발행 대기 중
        PUBLISHED,  // 발행 완료
        FAILED      // 발행 실패
    }
}
