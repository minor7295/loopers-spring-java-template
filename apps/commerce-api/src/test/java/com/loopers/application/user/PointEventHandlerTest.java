package com.loopers.application.user;

import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.PointEvent;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("PointEventHandler 포인트 사용 검증")
@RecordApplicationEvents
class PointEventHandlerTest {

    @Autowired
    private PointEventHandler pointEventHandler;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private ApplicationEvents applicationEvents;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // Helper methods for test fixtures
    private User createAndSaveUser(String userId, String email, long point) {
        User user = User.of(userId, email, "1990-01-01", Gender.MALE, Point.of(point));
        return userRepository.save(user);
    }

    @Test
    @DisplayName("포인트를 정상적으로 사용할 수 있다")
    void handleOrderCreated_success() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
                1L,
                user.getId(),
                null, // couponCode
                10_000, // subtotal
                10_000L, // usedPointAmount
                List.of(), // orderItems
                LocalDateTime.now()
        );

        // act
        pointEventHandler.handleOrderCreated(event);

        // assert
        // 포인트 사용 실패 이벤트는 발행되지 않아야 함
        assertThat(applicationEvents.stream(PointEvent.PointUsedFailed.class)).isEmpty();

        // 포인트가 차감되었는지 확인
        User savedUser = Optional.ofNullable(userRepository.findById(user.getId()))
                .orElseThrow(() -> new AssertionError("사용자를 찾을 수 없습니다."));
        assertThat(savedUser.getPointValue()).isEqualTo(40_000L); // 50,000 - 10,000
    }

    @Test
    @DisplayName("포인트 잔액이 부족하면 포인트 사용 실패 이벤트가 발행된다")
    void handleOrderCreated_publishesFailedEvent_whenInsufficientBalance() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 5_000L);
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
                1L,
                user.getId(),
                null, // couponCode
                10_000, // subtotal
                10_000L, // usedPointAmount
                List.of(), // orderItems
                LocalDateTime.now()
        );

        // act
        try {
            pointEventHandler.handleOrderCreated(event);
        } catch (Exception e) {
            // 예외는 예상된 동작
        }

        // assert
        // 포인트 사용 실패 이벤트가 발행되었는지 확인
        assertThat(applicationEvents.stream(PointEvent.PointUsedFailed.class)).hasSize(1);
        PointEvent.PointUsedFailed failedEvent = applicationEvents.stream(PointEvent.PointUsedFailed.class)
                .findFirst()
                .orElseThrow();
        assertThat(failedEvent.orderId()).isEqualTo(1L);
        assertThat(failedEvent.userId()).isEqualTo(user.getId());
        assertThat(failedEvent.usedPointAmount()).isEqualTo(10_000L);
        assertThat(failedEvent.failureReason()).contains("포인트가 부족합니다");

        // 포인트가 차감되지 않았는지 확인
        User savedUser = Optional.ofNullable(userRepository.findById(user.getId()))
                .orElseThrow(() -> new AssertionError("사용자를 찾을 수 없습니다."));
        assertThat(savedUser.getPointValue()).isEqualTo(5_000L); // 변경 없음
    }

    @Test
    @DisplayName("포인트 잔액이 정확히 사용 요청 금액과 같으면 정상적으로 사용할 수 있다")
    void handleOrderCreated_success_whenBalanceEqualsUsedAmount() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 10_000L);
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
                1L,
                user.getId(),
                null, // couponCode
                10_000, // subtotal
                10_000L, // usedPointAmount
                List.of(), // orderItems
                LocalDateTime.now()
        );

        // act
        pointEventHandler.handleOrderCreated(event);

        // assert
        // 포인트 사용 실패 이벤트는 발행되지 않아야 함
        assertThat(applicationEvents.stream(PointEvent.PointUsedFailed.class)).isEmpty();

        // 포인트가 차감되었는지 확인
        User savedUser = Optional.ofNullable(userRepository.findById(user.getId()))
                .orElseThrow(() -> new AssertionError("사용자를 찾을 수 없습니다."));
        assertThat(savedUser.getPointValue()).isEqualTo(0L); // 10,000 - 10,000
    }

    @Test
    @DisplayName("포인트 사용량이 0이면 정상적으로 처리된다")
    void handleOrderCreated_success_whenUsedAmountIsZero() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
                1L,
                user.getId(),
                null, // couponCode
                10_000, // subtotal
                0L, // usedPointAmount
                List.of(), // orderItems
                LocalDateTime.now()
        );

        // act
        pointEventHandler.handleOrderCreated(event);

        // assert
        // 포인트 사용 실패 이벤트는 발행되지 않아야 함
        assertThat(applicationEvents.stream(PointEvent.PointUsedFailed.class)).isEmpty();

        // 포인트가 변경되지 않았는지 확인
        User savedUser = Optional.ofNullable(userRepository.findById(user.getId()))
                .orElseThrow(() -> new AssertionError("사용자를 찾을 수 없습니다."));
        assertThat(savedUser.getPointValue()).isEqualTo(50_000L); // 변경 없음
    }
}

