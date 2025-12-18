package com.loopers.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OutboxEventPublisher 테스트.
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxEventPublisher outboxEventPublisher;

    @DisplayName("PENDING 상태의 이벤트를 Kafka로 발행할 수 있다.")
    @Test
    void canPublishPendingEvents() throws Exception {
        // arrange
        OutboxEvent event1 = createPendingEvent("event-1", "order-events", "1");
        OutboxEvent event2 = createPendingEvent("event-2", "like-events", "1");
        List<OutboxEvent> pendingEvents = List.of(event1, event2);

        when(outboxEventRepository.findPendingEvents(100)).thenReturn(pendingEvents);
        when(objectMapper.readValue(anyString(), eq(Object.class)))
            .thenReturn(Map.of("orderId", 1));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
            .thenReturn(createSuccessFuture());
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        outboxEventPublisher.publishPendingEvents();

        // assert
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), any());
        verify(outboxEventRepository, times(2)).save(any(OutboxEvent.class));
        
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(2)).save(captor.capture());
        
        List<OutboxEvent> savedEvents = captor.getAllValues();
        assertThat(savedEvents).allMatch(e -> 
            e.getStatus() == OutboxEvent.OutboxStatus.PUBLISHED
        );
        assertThat(savedEvents).allMatch(e -> 
            e.getPublishedAt() != null
        );
    }

    @DisplayName("PENDING 이벤트가 없으면 아무것도 발행하지 않는다.")
    @Test
    void doesNothing_whenNoPendingEvents() {
        // arrange
        when(outboxEventRepository.findPendingEvents(100)).thenReturn(List.of());

        // act
        outboxEventPublisher.publishPendingEvents();

        // assert
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @DisplayName("개별 이벤트 발행 실패 시에도 배치 처리를 계속한다.")
    @Test
    void continuesProcessing_whenIndividualEventFails() throws Exception {
        // arrange
        OutboxEvent event1 = createPendingEvent("event-1", "order-events", "1");
        OutboxEvent event2 = createPendingEvent("event-2", "like-events", "1");
        List<OutboxEvent> pendingEvents = List.of(event1, event2);

        when(outboxEventRepository.findPendingEvents(100)).thenReturn(pendingEvents);
        when(objectMapper.readValue(anyString(), eq(Object.class)))
            .thenReturn(Map.of("orderId", 1));
        when(kafkaTemplate.send(eq("order-events"), anyString(), any()))
            .thenThrow(new RuntimeException("Kafka 발행 실패"));
        when(kafkaTemplate.send(eq("like-events"), anyString(), any()))
            .thenReturn(createSuccessFuture());
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        outboxEventPublisher.publishPendingEvents();

        // assert
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), any());
        verify(outboxEventRepository, times(2)).save(any(OutboxEvent.class));
        
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(2)).save(captor.capture());
        
        List<OutboxEvent> savedEvents = captor.getAllValues();
        // event1은 FAILED, event2는 PUBLISHED
        assertThat(savedEvents).anyMatch(e -> 
            e.getEventId().equals("event-1") && 
            e.getStatus() == OutboxEvent.OutboxStatus.FAILED
        );
        assertThat(savedEvents).anyMatch(e -> 
            e.getEventId().equals("event-2") && 
            e.getStatus() == OutboxEvent.OutboxStatus.PUBLISHED
        );
    }

    @DisplayName("Kafka 발행 성공 시 이벤트 상태를 PUBLISHED로 변경한다.")
    @Test
    void marksAsPublished_whenKafkaPublishSucceeds() throws Exception {
        // arrange
        OutboxEvent event = createPendingEvent("event-1", "order-events", "1");
        List<OutboxEvent> pendingEvents = List.of(event);

        when(outboxEventRepository.findPendingEvents(100)).thenReturn(pendingEvents);
        when(objectMapper.readValue(anyString(), eq(Object.class)))
            .thenReturn(Map.of("orderId", 1));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
            .thenReturn(createSuccessFuture());
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        outboxEventPublisher.publishPendingEvents();

        // assert
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        
        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        assertThat(savedEvent.getPublishedAt()).isNotNull();
    }

    @DisplayName("Kafka 발행 실패 시 이벤트 상태를 FAILED로 변경한다.")
    @Test
    void marksAsFailed_whenKafkaPublishFails() throws Exception {
        // arrange
        OutboxEvent event = createPendingEvent("event-1", "order-events", "1");
        List<OutboxEvent> pendingEvents = List.of(event);

        when(outboxEventRepository.findPendingEvents(100)).thenReturn(pendingEvents);
        when(objectMapper.readValue(anyString(), eq(Object.class)))
            .thenReturn(Map.of("orderId", 1));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("Kafka 발행 실패"));
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        outboxEventPublisher.publishPendingEvents();

        // assert
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        
        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.FAILED);
        assertThat(savedEvent.getPublishedAt()).isNull();
    }

    @DisplayName("JSON 역직렬화 실패 시 이벤트 상태를 FAILED로 변경한다.")
    @Test
    void marksAsFailed_whenJsonDeserializationFails() throws Exception {
        // arrange
        OutboxEvent event = createPendingEvent("event-1", "order-events", "1");
        List<OutboxEvent> pendingEvents = List.of(event);

        when(outboxEventRepository.findPendingEvents(100)).thenReturn(pendingEvents);
        when(objectMapper.readValue(anyString(), eq(Object.class)))
            .thenThrow(new RuntimeException("JSON 역직렬화 실패"));
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        outboxEventPublisher.publishPendingEvents();

        // assert
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        
        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.FAILED);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @DisplayName("배치 크기만큼 이벤트를 조회한다.")
    @Test
    void queriesEventsWithBatchSize() {
        // arrange
        when(outboxEventRepository.findPendingEvents(100)).thenReturn(List.of());

        // act
        outboxEventPublisher.publishPendingEvents();

        // assert
        verify(outboxEventRepository).findPendingEvents(100);
    }

    @DisplayName("각 토픽에 적절한 파티션 키를 사용하여 Kafka로 발행한다.")
    @Test
    void usesCorrectPartitionKeyForEachTopic() throws Exception {
        // arrange
        OutboxEvent likeEvent = createPendingEvent("event-1", "like-events", "product-123");
        OutboxEvent orderEvent = createPendingEvent("event-2", "order-events", "order-456");
        OutboxEvent productEvent = createPendingEvent("event-3", "product-events", "product-789");
        List<OutboxEvent> pendingEvents = List.of(likeEvent, orderEvent, productEvent);

        when(outboxEventRepository.findPendingEvents(100)).thenReturn(pendingEvents);
        when(objectMapper.readValue(anyString(), eq(Object.class)))
            .thenReturn(Map.of("productId", 123));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
            .thenReturn(createSuccessFuture());
        when(outboxEventRepository.save(any(OutboxEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        outboxEventPublisher.publishPendingEvents();

        // assert - 각 토픽에 올바른 파티션 키가 전달되는지 검증
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> partitionKeyCaptor = ArgumentCaptor.forClass(String.class);
        
        verify(kafkaTemplate, times(3)).send(
            topicCaptor.capture(), 
            partitionKeyCaptor.capture(), 
            any()
        );
        
        List<String> topics = topicCaptor.getAllValues();
        List<String> partitionKeys = partitionKeyCaptor.getAllValues();
        
        // like-events는 productId를 파티션 키로 사용
        int likeIndex = topics.indexOf("like-events");
        assertThat(likeIndex).isNotEqualTo(-1);
        assertThat(partitionKeys.get(likeIndex)).isEqualTo("product-123");
        
        // order-events는 orderId를 파티션 키로 사용
        int orderIndex = topics.indexOf("order-events");
        assertThat(orderIndex).isNotEqualTo(-1);
        assertThat(partitionKeys.get(orderIndex)).isEqualTo("order-456");
        
        // product-events는 productId를 파티션 키로 사용
        int productIndex = topics.indexOf("product-events");
        assertThat(productIndex).isNotEqualTo(-1);
        assertThat(partitionKeys.get(productIndex)).isEqualTo("product-789");
    }

    /**
     * PENDING 상태의 OutboxEvent를 생성합니다.
     */
    private OutboxEvent createPendingEvent(String eventId, String topic, String partitionKey) {
        return OutboxEvent.builder()
            .eventId(eventId)
            .eventType("OrderCreated")
            .aggregateId("1")
            .aggregateType("Order")
            .payload("{\"orderId\":1}")
            .topic(topic)
            .partitionKey(partitionKey)
            .build();
    }

    /**
     * Kafka 발행 성공을 시뮬레이션하는 CompletableFuture를 생성합니다.
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, Object>> createSuccessFuture() {
        return (CompletableFuture<SendResult<String, Object>>) (CompletableFuture<?>) 
            CompletableFuture.completedFuture(null);
    }
}
