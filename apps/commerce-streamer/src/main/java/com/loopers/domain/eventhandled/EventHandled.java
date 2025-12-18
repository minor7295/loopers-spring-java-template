package com.loopers.domain.eventhandled;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 이벤트 처리 기록 엔티티.
 * <p>
 * Kafka Consumer에서 처리한 이벤트의 멱등성을 보장하기 위한 엔티티입니다.
 * `eventId`를 Primary Key로 사용하여 중복 처리를 방지합니다.
 * </p>
 * <p>
 * <b>멱등성 보장:</b>
 * <ul>
 *   <li>동일한 `eventId`를 가진 이벤트는 한 번만 처리됩니다</li>
 *   <li>UNIQUE 제약조건으로 데이터베이스 레벨에서 중복 방지</li>
 *   <li>이벤트 처리 전 `eventId` 존재 여부를 확인하여 중복 처리 방지</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Entity
@Table(name = "event_handled", indexes = {
    @Index(name = "idx_handled_at", columnList = "handled_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class EventHandled {

    @Id
    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    @Column(name = "handled_at", nullable = false)
    private LocalDateTime handledAt;

    /**
     * EventHandled 인스턴스를 생성합니다.
     *
     * @param eventId 이벤트 ID (UUID)
     * @param eventType 이벤트 타입 (예: "LikeAdded", "OrderCreated")
     * @param topic Kafka 토픽 이름
     */
    public EventHandled(String eventId, String eventType, String topic) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.topic = topic;
        this.handledAt = LocalDateTime.now();
    }
}
