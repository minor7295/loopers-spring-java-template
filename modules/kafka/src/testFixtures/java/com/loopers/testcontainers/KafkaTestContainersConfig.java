package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Kafka Testcontainers 설정.
 * <p>
 * 테스트 실행 시 자동으로 Kafka 컨테이너를 시작하고,
 * Spring Boot의 Kafka 설정에 동적으로 포트를 주입합니다.
 * </p>
 * <p>
 * <b>동작 방식:</b>
 * 1. Kafka 컨테이너를 시작
 * 2. 동적으로 할당된 포트를 System Property로 설정
 * 3. kafka.yml의 ${BOOTSTRAP_SERVERS}가 이 값을 사용
 * </p>
 */
@Configuration
public class KafkaTestContainersConfig {

    private static final ConfluentKafkaContainer kafkaContainer;

    static {
        // Kafka 컨테이너 생성 및 시작
        // ConfluentKafkaContainer는 confluentinc/cp-kafka 이미지를 사용
        kafkaContainer = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.5.0");
        kafkaContainer.start();

        // Spring Boot의 Kafka 설정에 동적으로 포트 주입
        // kafka.yml의 ${BOOTSTRAP_SERVERS}가 이 값을 사용
        String bootstrapServers = kafkaContainer.getBootstrapServers();
        System.setProperty("BOOTSTRAP_SERVERS", bootstrapServers);
    }
}
