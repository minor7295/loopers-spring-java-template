package com.loopers.utils;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DeleteConsumerGroupsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Kafka 테스트 정리 유틸리티.
 * <p>
 * 테스트 간 Kafka 메시지 격리를 위해 토픽을 삭제하고 재생성합니다.
 * </p>
 * <p>
 * <b>사용 방법:</b>
 * <ul>
 *   <li>통합 테스트에서 `@BeforeEach` 또는 `@AfterEach`에서 호출하여 테스트 간 격리 보장</li>
 *   <li>단위 테스트는 Mock을 사용하므로 불필요</li>
 * </ul>
 * </p>
 * <p>
 * <b>주의:</b>
 * 프로덕션 환경에서는 사용하지 마세요. 테스트 환경에서만 사용해야 합니다.
 * </p>
 */
@Component
public class KafkaCleanUp {

    private static final List<String> TEST_TOPICS = List.of(
        "like-events",
        "order-events",
        "product-events",
        "payment-events",
        "coupon-events",
        "user-events"
    );

    private final KafkaAdmin kafkaAdmin;

    public KafkaCleanUp(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    /**
     * 테스트용 토픽의 모든 메시지를 삭제합니다.
     * <p>
     * 토픽을 삭제하고 재생성하여 모든 메시지를 제거합니다.
     * </p>
     * <p>
     * <b>주의:</b> 프로덕션 환경에서는 사용하지 마세요.
     * </p>
     */
    public void deleteAllTestTopics() {
        try (AdminClient adminClient = createAdminClient()) {
            // 존재하는 토픽만 삭제
            Set<String> existingTopics = adminClient.listTopics()
                .names()
                .get(5, TimeUnit.SECONDS);

            List<String> topicsToDelete = TEST_TOPICS.stream()
                .filter(existingTopics::contains)
                .toList();

            if (topicsToDelete.isEmpty()) {
                return;
            }

            // 토픽 삭제 (모든 메시지 제거)
            DeleteTopicsResult deleteResult = adminClient.deleteTopics(topicsToDelete);
            deleteResult.all().get(10, TimeUnit.SECONDS);

            // 토픽 삭제 후 재생성 대기 (Kafka가 토픽 삭제를 완료할 때까지)
            Thread.sleep(1000);
        } catch (Exception e) {
            // 토픽이 없거나 이미 삭제된 경우 무시
            // 테스트 환경에서는 토픽이 없을 수 있음
        }
    }

    /**
     * 테스트용 토픽을 재생성합니다.
     * <p>
     * 삭제된 토픽을 원래 설정으로 재생성합니다.
     * </p>
     */
    public void recreateTestTopics() {
        try (AdminClient adminClient = createAdminClient()) {
            for (String topicName : TEST_TOPICS) {
                try {
                    // 토픽이 이미 존재하는지 확인
                    adminClient.describeTopics(Collections.singletonList(topicName))
                        .allTopicNames()
                        .get(2, TimeUnit.SECONDS);
                    // 이미 존재하면 스킵
                    continue;
                } catch (Exception e) {
                    // 토픽이 없으면 생성
                }

                // 토픽 생성
                NewTopic newTopic = TopicBuilder.name(topicName)
                    .partitions(3)
                    .replicas(1)
                    .config("min.insync.replicas", "1")
                    .build();

                adminClient.createTopics(Collections.singletonList(newTopic))
                    .all()
                    .get(5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // 토픽 생성 실패는 무시 (이미 존재할 수 있음)
        }
    }

    /**
     * 테스트용 토픽을 삭제하고 재생성합니다.
     * <p>
     * 모든 메시지를 제거하고 깨끗한 상태로 시작합니다.
     * </p>
     */
    public void resetAllTestTopics() {
        deleteAllTestTopics();
        recreateTestTopics();
    }

    /**
     * 모든 Consumer Group을 삭제하여 offset을 리셋합니다.
     * <p>
     * 테스트 간 격리를 위해 사용합니다.
     * </p>
     * <p>
     * <b>주의:</b> 모든 Consumer Group을 삭제하므로 프로덕션 환경에서는 사용하지 마세요.
     * </p>
     */
    public void resetAllConsumerGroups() {
        try (AdminClient adminClient = createAdminClient()) {
            // 모든 Consumer Group 목록 조회
            Set<String> consumerGroups = adminClient.listConsumerGroups()
                .all()
                .get(5, TimeUnit.SECONDS)
                .stream()
                .map(group -> group.groupId())
                .collect(java.util.stream.Collectors.toSet());

            if (consumerGroups.isEmpty()) {
                return;
            }

            // Consumer Group 삭제 (offset 리셋)
            DeleteConsumerGroupsResult deleteResult = adminClient.deleteConsumerGroups(consumerGroups);
            deleteResult.all().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Consumer Group이 없거나 이미 삭제된 경우 무시
            // 테스트 환경에서는 Consumer Group이 없을 수 있음
        }
    }

    /**
     * 특정 Consumer Group을 삭제합니다.
     *
     * @param groupId 삭제할 Consumer Group ID
     */
    public void resetConsumerGroup(String groupId) {
        try (AdminClient adminClient = createAdminClient()) {
            DeleteConsumerGroupsResult deleteResult = adminClient.deleteConsumerGroups(
                Collections.singletonList(groupId)
            );
            deleteResult.all().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Consumer Group이 없거나 이미 삭제된 경우 무시
        }
    }

    /**
     * AdminClient를 생성합니다.
     */
    private AdminClient createAdminClient() {
        Properties props = new Properties();
        Object bootstrapServers = kafkaAdmin.getConfigurationProperties()
            .getOrDefault(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:19092");
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return AdminClient.create(props);
    }
}
