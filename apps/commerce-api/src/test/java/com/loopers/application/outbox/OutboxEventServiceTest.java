package com.loopers.application.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OutboxEventService 테스트.
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxEventService outboxEventService;

    @DisplayName("이벤트를 Outbox에 저장할 수 있다.")
    @Test
    void canSaveEvent() throws Exception {
        // arrange
        String eventType = "LikeAdded";
        String aggregateId = "1";
        String aggregateType = "Product";
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(100L, 1L, LocalDateTime.now());
        String topic = "like-events";
        String partitionKey = "1";
        String payload = "{\"userId\":100,\"productId\":1}";

        when(objectMapper.writeValueAsString(event)).thenReturn(payload);
        when(outboxEventRepository.findLatestVersionByAggregateId(aggregateId, aggregateType))
            .thenReturn(0L);
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        outboxEventService.saveEvent(eventType, aggregateId, aggregateType, event, topic, partitionKey);

        // assert
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        
        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getEventType()).isEqualTo(eventType);
        assertThat(savedEvent.getAggregateId()).isEqualTo(aggregateId);
        assertThat(savedEvent.getAggregateType()).isEqualTo(aggregateType);
        assertThat(savedEvent.getPayload()).isEqualTo(payload);
        assertThat(savedEvent.getTopic()).isEqualTo(topic);
        assertThat(savedEvent.getPartitionKey()).isEqualTo(partitionKey);
        assertThat(savedEvent.getVersion()).isEqualTo(1L); // 최신 버전(0) + 1
        assertThat(savedEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(savedEvent.getEventId()).isNotNull();
        assertThat(savedEvent.getCreatedAt()).isNotNull();
    }

    @DisplayName("이벤트 저장 시 UUID로 고유한 eventId가 생성된다.")
    @Test
    void generatesUniqueEventId() throws Exception {
        // arrange
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(100L, 1L, LocalDateTime.now());
        when(objectMapper.writeValueAsString(event)).thenReturn("{}");
        when(outboxEventRepository.findLatestVersionByAggregateId(anyString(), anyString()))
            .thenReturn(0L);
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        outboxEventService.saveEvent("LikeAdded", "1", "Product", event, "like-events", "1");
        outboxEventService.saveEvent("LikeAdded", "2", "Product", event, "like-events", "2");

        // assert
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(2)).save(captor.capture());
        
        OutboxEvent event1 = captor.getAllValues().get(0);
        OutboxEvent event2 = captor.getAllValues().get(1);
        assertThat(event1.getEventId()).isNotEqualTo(event2.getEventId());
    }

    @DisplayName("같은 집계 ID에 대해 버전이 순차적으로 증가한다.")
    @Test
    void incrementsVersionSequentially() throws Exception {
        // arrange
        String aggregateId = "1";
        String aggregateType = "Product";
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(100L, 1L, LocalDateTime.now());
        when(objectMapper.writeValueAsString(event)).thenReturn("{}");
        when(outboxEventRepository.findLatestVersionByAggregateId(aggregateId, aggregateType))
            .thenReturn(0L)  // 첫 번째 호출: 최신 버전 0
            .thenReturn(1L)  // 두 번째 호출: 최신 버전 1
            .thenReturn(2L); // 세 번째 호출: 최신 버전 2
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        outboxEventService.saveEvent("LikeAdded", aggregateId, aggregateType, event, "like-events", aggregateId);
        outboxEventService.saveEvent("LikeRemoved", aggregateId, aggregateType, event, "like-events", aggregateId);
        outboxEventService.saveEvent("ProductViewed", aggregateId, aggregateType, event, "product-events", aggregateId);

        // assert
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(3)).save(captor.capture());
        
        OutboxEvent event1 = captor.getAllValues().get(0);
        OutboxEvent event2 = captor.getAllValues().get(1);
        OutboxEvent event3 = captor.getAllValues().get(2);
        
        assertThat(event1.getVersion()).isEqualTo(1L);
        assertThat(event2.getVersion()).isEqualTo(2L);
        assertThat(event3.getVersion()).isEqualTo(3L);
    }

    @DisplayName("JSON 직렬화 실패 시 예외를 발생시킨다.")
    @Test
    void throwsException_whenJsonSerializationFails() throws Exception {
        // arrange
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(100L, 1L, LocalDateTime.now());
        when(objectMapper.writeValueAsString(event))
            .thenThrow(new RuntimeException("JSON 직렬화 실패"));

        // act & assert
        assertThatThrownBy(() -> 
            outboxEventService.saveEvent("LikeAdded", "1", "Product", event, "like-events", "1")
        ).isInstanceOf(RuntimeException.class)
         .hasMessageContaining("Outbox 이벤트 저장 실패");

        verify(outboxEventRepository, never()).save(any());
    }

    @DisplayName("Repository 저장 실패 시 예외를 발생시킨다.")
    @Test
    void throwsException_whenRepositorySaveFails() throws Exception {
        // arrange
        LikeEvent.LikeAdded event = new LikeEvent.LikeAdded(100L, 1L, LocalDateTime.now());
        when(objectMapper.writeValueAsString(event)).thenReturn("{}");
        when(outboxEventRepository.findLatestVersionByAggregateId("1", "Product"))
            .thenReturn(0L);
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenThrow(new RuntimeException("DB 저장 실패"));

        // act & assert
        assertThatThrownBy(() -> 
            outboxEventService.saveEvent("LikeAdded", "1", "Product", event, "like-events", "1")
        ).isInstanceOf(RuntimeException.class)
         .hasMessageContaining("Outbox 이벤트 저장 실패");

        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }
}
