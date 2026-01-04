package com.loopers.application.eventhandled;

import com.loopers.domain.eventhandled.EventHandled;
import com.loopers.domain.eventhandled.EventHandledRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트 처리 기록 서비스.
 * <p>
 * Kafka Consumer에서 이벤트의 멱등성을 보장하기 위한 서비스입니다.
 * 이벤트 처리 전 `eventId`가 이미 처리되었는지 확인하고,
 * 처리되지 않은 경우에만 처리 기록을 저장합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventHandledService {

    private final EventHandledRepository eventHandledRepository;

    /**
     * 이벤트가 이미 처리되었는지 확인합니다.
     *
     * @param eventId 이벤트 ID
     * @return 이미 처리된 경우 true, 그렇지 않으면 false
     */
    @Transactional(readOnly = true)
    public boolean isAlreadyHandled(String eventId) {
        return eventHandledRepository.existsByEventId(eventId);
    }

    /**
     * 이벤트 처리 기록을 저장합니다.
     * <p>
     * UNIQUE 제약조건 위반 시 예외를 발생시킵니다.
     * 이는 동시성 상황에서 중복 처리를 방지하기 위한 것입니다.
     * </p>
     *
     * @param eventId 이벤트 ID
     * @param eventType 이벤트 타입 (예: "LikeAdded", "OrderCreated")
     * @param topic Kafka 토픽 이름
     * @throws org.springframework.dao.DataIntegrityViolationException 이미 처리된 이벤트인 경우
     */
    @Transactional
    public void markAsHandled(String eventId, String eventType, String topic) {
        try {
            EventHandled eventHandled = new EventHandled(eventId, eventType, topic);
            eventHandledRepository.save(eventHandled);
            log.debug("이벤트 처리 기록 저장: eventId={}, eventType={}, topic={}", 
                eventId, eventType, topic);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // UNIQUE 제약조건 위반 = 이미 처리됨 (멱등성 보장)
            log.warn("이벤트가 이미 처리되었습니다: eventId={}", eventId);
            throw e;
        }
    }
}
