package com.loopers.infrastructure.outbox;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.KafkaCleanUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OutboxEventPublisher 통합 테스트.
 * <p>
 * 실제 Kafka를 사용하여 Outbox 패턴의 이벤트 발행 동작을 검증합니다.
 * </p>
 * <p>
 * <b>Kafka 컨테이너:</b>
 * {@link KafkaTestContainersConfig}가 테스트 실행 시 자동으로 Kafka 컨테이너를 시작합니다.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(KafkaTestContainersConfig.class)
class OutboxEventPublisherIntegrationTest {

    @Autowired
    private KafkaCleanUp kafkaCleanUp;

    @BeforeEach
    void setUp() {
        // 테스트 전에 토픽의 모든 메시지 삭제 및 재생성
        kafkaCleanUp.resetAllTestTopics();
    }

    @DisplayName("통합 테스트: Outbox 패턴을 통한 Kafka 이벤트 발행이 정상적으로 동작한다.")
    @Test
    void integrationTest() {
        // TODO: 실제 Kafka를 사용한 통합 테스트 구현
        // 예: OutboxEvent를 저장한 후 OutboxEventPublisher가 Kafka로 발행하는지 확인
    }
}
