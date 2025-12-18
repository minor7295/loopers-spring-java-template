package com.loopers.application.eventhandled;

import com.loopers.domain.eventhandled.EventHandled;
import com.loopers.domain.eventhandled.EventHandledRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EventHandledService 테스트.
 */
@ExtendWith(MockitoExtension.class)
class EventHandledServiceTest {

    @Mock
    private EventHandledRepository eventHandledRepository;

    @InjectMocks
    private EventHandledService eventHandledService;

    @DisplayName("처리되지 않은 이벤트는 false를 반환한다.")
    @Test
    void isAlreadyHandled_returnsFalse_whenNotHandled() {
        // arrange
        String eventId = "test-event-id";
        when(eventHandledRepository.existsByEventId(eventId)).thenReturn(false);

        // act
        boolean result = eventHandledService.isAlreadyHandled(eventId);

        // assert
        assertThat(result).isFalse();
        verify(eventHandledRepository).existsByEventId(eventId);
    }

    @DisplayName("이미 처리된 이벤트는 true를 반환한다.")
    @Test
    void isAlreadyHandled_returnsTrue_whenAlreadyHandled() {
        // arrange
        String eventId = "test-event-id";
        when(eventHandledRepository.existsByEventId(eventId)).thenReturn(true);

        // act
        boolean result = eventHandledService.isAlreadyHandled(eventId);

        // assert
        assertThat(result).isTrue();
        verify(eventHandledRepository).existsByEventId(eventId);
    }

    @DisplayName("처리되지 않은 이벤트는 정상적으로 저장된다.")
    @Test
    void markAsHandled_savesSuccessfully_whenNotHandled() {
        // arrange
        String eventId = "test-event-id";
        String eventType = "LikeAdded";
        String topic = "like-events";
        
        EventHandled savedEventHandled = new EventHandled(eventId, eventType, topic);
        when(eventHandledRepository.save(any(EventHandled.class))).thenReturn(savedEventHandled);

        // act
        eventHandledService.markAsHandled(eventId, eventType, topic);

        // assert
        verify(eventHandledRepository).save(any(EventHandled.class));
    }

    @DisplayName("이미 처리된 이벤트는 DataIntegrityViolationException을 발생시킨다.")
    @Test
    void markAsHandled_throwsException_whenAlreadyHandled() {
        // arrange
        String eventId = "test-event-id";
        String eventType = "LikeAdded";
        String topic = "like-events";
        
        when(eventHandledRepository.save(any(EventHandled.class)))
            .thenThrow(new DataIntegrityViolationException("UNIQUE constraint violation"));

        // act & assert
        assertThatThrownBy(() -> 
            eventHandledService.markAsHandled(eventId, eventType, topic)
        ).isInstanceOf(DataIntegrityViolationException.class);

        verify(eventHandledRepository).save(any(EventHandled.class));
    }
}
