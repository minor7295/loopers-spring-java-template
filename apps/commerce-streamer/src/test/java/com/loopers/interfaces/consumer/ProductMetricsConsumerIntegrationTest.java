package com.loopers.interfaces.consumer;

import com.loopers.domain.event.LikeEvent;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.KafkaCleanUp;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProductMetricsConsumer 통합 테스트.
 * <p>
 * 실제 Kafka를 사용하여 이벤트 처리 동작을 검증합니다.
 * </p>
 * <p>
 * <b>Kafka 컨테이너:</b>
 * {@link KafkaTestContainersConfig}가 테스트 실행 시 자동으로 Kafka 컨테이너를 시작합니다.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(KafkaTestContainersConfig.class)
class ProductMetricsConsumerIntegrationTest {

    @Autowired
    private KafkaCleanUp kafkaCleanUp;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaProperties kafkaProperties;

    @BeforeEach
    void setUp() {
        // 테스트 전에 토픽의 모든 메시지 삭제 및 재생성
        kafkaCleanUp.resetAllTestTopics();
    }

    /**
     * offset.reset: latest 설정이 제대로 적용되는지 확인하는 테스트.
     * <p>
     * <b>테스트 목적:</b>
     * kafka.yml에 설정된 `offset.reset: latest`가 실제로 동작하는지 검증합니다.
     * </p>
     * <p>
     * <b>동작 원리:</b>
     * 1. 이전 메시지를 Kafka에 발행 (이 메시지는 나중에 읽히지 않아야 함)
     * 2. Consumer Group을 삭제하여 offset 정보 제거
     * 3. 새로운 메시지를 Kafka에 발행
     * 4. 새로운 Consumer Group으로 Consumer를 시작
     * 5. offset.reset: latest 설정으로 인해 Consumer는 최신 메시지(새로운 메시지)부터 읽기 시작해야 함
     * </p>
     * <p>
     * <b>검증 내용:</b>
     * - Consumer의 현재 position이 최신 offset(endOffset)과 같거나 가까운지 확인
     * - 이는 Consumer가 이전 메시지를 건너뛰고 최신 메시지부터 읽기 시작했다는 의미
     * </p>
     */
    @DisplayName("offset.reset: latest 설정이 적용되어 새로운 Consumer Group은 최신 메시지만 읽는다.")
    @Test
    void offsetResetLatest_shouldOnlyReadLatestMessages() throws Exception {
        // 이 메시지는 나중에 Consumer가 읽지 않아야 함 (offset.reset: latest 때문)
        String topic = "like-events";
        String partitionKey = "product-1";
        LikeEvent.LikeAdded oldMessage = new LikeEvent.LikeAdded(100L, 1L, LocalDateTime.now());
        kafkaTemplate.send(topic, partitionKey, oldMessage).get();

        // Consumer Group을 삭제하면 offset 정보가 사라짐
        // 다음에 같은 Consumer Group으로 시작할 때 offset.reset 설정이 적용됨
        String testGroupId = "test-offset-reset-" + System.currentTimeMillis();
        kafkaCleanUp.resetConsumerGroup(testGroupId);

        // 이 메시지는 Consumer가 읽어야 함 (최신 메시지이므로)
        LikeEvent.LikeAdded newMessage = new LikeEvent.LikeAdded(200L, 1L, LocalDateTime.now());
        kafkaTemplate.send(topic, partitionKey, newMessage).get();

        // 프로젝트의 kafka.yml 설정을 사용하여 Consumer 생성
        // 이 설정에는 offset.reset: latest가 포함되어 있음
        Map<String, Object> consumerProps = kafkaProperties.buildConsumerProperties();
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, testGroupId);
        
        try (KafkaConsumer<String, Object> consumer = new KafkaConsumer<>(consumerProps)) {
            // 특정 파티션에 할당 (테스트용)
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(Collections.singletonList(partition));

            // endOffset: 토픽의 마지막 메시지 다음 offset (현재는 2개 메시지가 있으므로 2)
            // currentPosition: Consumer가 현재 읽을 위치 (offset.reset: latest면 endOffset과 같아야 함)
            Long endOffset = consumer.endOffsets(Collections.singletonList(partition)).get(partition);
            long currentPosition = consumer.position(partition);

            // offset.reset: latest 설정이 적용되었다면:
            // - currentPosition은 endOffset과 같거나 가까워야 함
            // - 이는 Consumer가 이전 메시지(oldMessage)를 건너뛰고 최신 메시지(newMessage)부터 읽기 시작했다는 의미
            // 예: endOffset=2, currentPosition=2 → 이전 메시지(offset 0)를 건너뛰고 최신 메시지(offset 1)부터 시작
            assertThat(currentPosition)
                .isGreaterThanOrEqualTo(endOffset - 1);
        }
    }
}
