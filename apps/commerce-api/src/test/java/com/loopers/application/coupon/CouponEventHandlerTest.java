package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponEvent;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponType;
import com.loopers.domain.coupon.UserCoupon;
import com.loopers.domain.coupon.UserCouponRepository;
import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("CouponEventHandler 쿠폰 적용 검증")
@RecordApplicationEvents
class CouponEventHandlerTest {

    @Autowired
    private com.loopers.interfaces.event.coupon.CouponEventListener couponEventListener;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private ApplicationEvents applicationEvents;

    // ✅ OrderEventListener를 Mocking하여 CouponEventHandlerTest에서 주문 관련 로직이 실행되지 않도록 함
    // CouponEventHandlerTest는 쿠폰 도메인의 책임만 테스트해야 하므로 주문 관련 로직은 제외
    @MockitoBean
    private com.loopers.interfaces.event.order.OrderEventListener orderEventListener;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // Helper methods for test fixtures
    private User createAndSaveUser(String userId, String email, long point) {
        User user = User.of(userId, email, "1990-01-01", Gender.MALE, Point.of(point));
        return userRepository.save(user);
    }

    private Coupon createAndSaveCoupon(String code, CouponType type, Integer discountValue) {
        Coupon coupon = Coupon.of(code, type, discountValue);
        return couponRepository.save(coupon);
    }

    private UserCoupon createAndSaveUserCoupon(Long userId, Coupon coupon) {
        UserCoupon userCoupon = UserCoupon.of(userId, coupon);
        return userCouponRepository.save(userCoupon);
    }

    @Test
    @DisplayName("쿠폰 코드가 없으면 처리하지 않는다")
    void handleOrderCreated_skips_whenNoCouponCode() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            1L,
            user.getId(),
            null, // 쿠폰 코드 없음
            10_000,
            0L,
            List.of(),
            LocalDateTime.now()
        );

        // act
        couponEventListener.handleOrderCreated(event);

        // assert
        // 예외 없이 처리되어야 함
    }

    @Test
    @DisplayName("정액 쿠폰을 정상적으로 적용할 수 있다")
    void handleOrderCreated_appliesFixedAmountCoupon_success() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Coupon coupon = createAndSaveCoupon("FIXED5000", CouponType.FIXED_AMOUNT, 5_000);
        createAndSaveUserCoupon(user.getId(), coupon);

        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            1L,
            user.getId(),
            "FIXED5000",
            10_000,
            0L,
            List.of(),
            LocalDateTime.now()
        );

        // act
        // CouponEventListener를 통해 호출하여 실제 운영 환경과 동일한 방식으로 테스트
        // CouponEventListener는 @Async와 @TransactionalEventListener(phase = AFTER_COMMIT)로 설정되어 있지만,
        // 테스트에서는 동기적으로 실행되도록 설정되어 있을 수 있음
        couponEventListener.handleOrderCreated(event);
        
        // 비동기 처리 대기
        Thread.sleep(100);

        // assert
        // 쿠폰 적용 성공 이벤트가 발행되었는지 확인
        assertThat(applicationEvents.stream(CouponEvent.CouponApplied.class)).hasSize(1);
        CouponEvent.CouponApplied appliedEvent = applicationEvents.stream(CouponEvent.CouponApplied.class)
                .findFirst()
                .orElseThrow();
        assertThat(appliedEvent.orderId()).isEqualTo(1L);
        assertThat(appliedEvent.userId()).isEqualTo(user.getId());
        assertThat(appliedEvent.couponCode()).isEqualTo("FIXED5000");
        assertThat(appliedEvent.discountAmount()).isEqualTo(5_000);
        
        // 쿠폰 적용 실패 이벤트는 발행되지 않아야 함
        assertThat(applicationEvents.stream(CouponEvent.CouponApplicationFailed.class)).isEmpty();
    }

    @Test
    @DisplayName("정률 쿠폰을 정상적으로 적용할 수 있다")
    void handleOrderCreated_appliesPercentageCoupon_success() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Coupon coupon = createAndSaveCoupon("PERCENT20", CouponType.PERCENTAGE, 20);
        createAndSaveUserCoupon(user.getId(), coupon);

        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            1L,
            user.getId(),
            "PERCENT20",
            10_000,
            0L,
            List.of(),
            LocalDateTime.now()
        );

        // act
        // CouponEventListener를 통해 호출하여 실제 운영 환경과 동일한 방식으로 테스트
        // CouponEventListener는 @Async와 @TransactionalEventListener(phase = AFTER_COMMIT)로 설정되어 있지만,
        // 테스트에서는 동기적으로 실행되도록 설정되어 있을 수 있음
        couponEventListener.handleOrderCreated(event);
        
        // 비동기 처리 대기
        Thread.sleep(100);

        // assert
        // 쿠폰 적용 성공 이벤트가 발행되었는지 확인
        assertThat(applicationEvents.stream(CouponEvent.CouponApplied.class)).hasSize(1);
        CouponEvent.CouponApplied appliedEvent = applicationEvents.stream(CouponEvent.CouponApplied.class)
                .findFirst()
                .orElseThrow();
        assertThat(appliedEvent.orderId()).isEqualTo(1L);
        assertThat(appliedEvent.userId()).isEqualTo(user.getId());
        assertThat(appliedEvent.couponCode()).isEqualTo("PERCENT20");
        assertThat(appliedEvent.discountAmount()).isEqualTo(2_000); // 10,000 * 20% = 2,000
        
        // 쿠폰 적용 실패 이벤트는 발행되지 않아야 함
        assertThat(applicationEvents.stream(CouponEvent.CouponApplicationFailed.class)).isEmpty();
        
        // 쿠폰이 사용되었는지 확인
        UserCoupon savedUserCoupon = userCouponRepository.findByUserIdAndCouponCode(user.getId(), "PERCENT20")
            .orElseThrow();
        assertThat(savedUserCoupon.getIsUsed()).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 쿠폰 코드로 주문하면 쿠폰 적용 실패 이벤트가 발행된다")
    void handleOrderCreated_publishesFailedEvent_whenCouponNotFound() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            1L,
            user.getId(),
            "NON_EXISTENT",
            10_000,
            0L,
            List.of(),
            LocalDateTime.now()
        );

        // act
        // CouponEventListener를 통해 호출하여 실제 운영 환경과 동일한 방식으로 테스트
        // CouponEventListener는 @Async와 @TransactionalEventListener(phase = AFTER_COMMIT)로 설정되어 있지만,
        // 테스트에서는 동기적으로 실행되도록 설정되어 있을 수 있음
        couponEventListener.handleOrderCreated(event);
        
        // 비동기 처리 대기
        Thread.sleep(100);

        // assert
        // 쿠폰 적용 실패 이벤트가 발행되었는지 확인
        assertThat(applicationEvents.stream(CouponEvent.CouponApplicationFailed.class)).hasSize(1);
        CouponEvent.CouponApplicationFailed failedEvent = applicationEvents.stream(CouponEvent.CouponApplicationFailed.class)
                .findFirst()
                .orElseThrow();
        assertThat(failedEvent.orderId()).isEqualTo(1L);
        assertThat(failedEvent.userId()).isEqualTo(user.getId());
        assertThat(failedEvent.couponCode()).isEqualTo("NON_EXISTENT");
        assertThat(failedEvent.failureReason()).contains("쿠폰을 찾을 수 없습니다");
        
        // 쿠폰 적용 성공 이벤트는 발행되지 않아야 함
        assertThat(applicationEvents.stream(CouponEvent.CouponApplied.class)).isEmpty();
    }

    @Test
    @DisplayName("사용자가 소유하지 않은 쿠폰으로 주문하면 쿠폰 적용 실패 이벤트가 발행된다")
    void handleOrderCreated_publishesFailedEvent_whenCouponNotOwnedByUser() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        createAndSaveCoupon("COUPON001", CouponType.FIXED_AMOUNT, 5_000);
        // 사용자에게 쿠폰을 지급하지 않음

        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            1L,
            user.getId(),
            "COUPON001",
            10_000,
            0L,
            List.of(),
            LocalDateTime.now()
        );

        // act
        // CouponEventListener를 통해 호출하여 실제 운영 환경과 동일한 방식으로 테스트
        // CouponEventListener는 @Async와 @TransactionalEventListener(phase = AFTER_COMMIT)로 설정되어 있지만,
        // 테스트에서는 동기적으로 실행되도록 설정되어 있을 수 있음
        couponEventListener.handleOrderCreated(event);
        
        // 비동기 처리 대기
        Thread.sleep(100);

        // assert
        // 쿠폰 적용 실패 이벤트가 발행되었는지 확인
        assertThat(applicationEvents.stream(CouponEvent.CouponApplicationFailed.class)).hasSize(1);
        CouponEvent.CouponApplicationFailed failedEvent = applicationEvents.stream(CouponEvent.CouponApplicationFailed.class)
                .findFirst()
                .orElseThrow();
        assertThat(failedEvent.orderId()).isEqualTo(1L);
        assertThat(failedEvent.userId()).isEqualTo(user.getId());
        assertThat(failedEvent.couponCode()).isEqualTo("COUPON001");
        assertThat(failedEvent.failureReason()).contains("사용자가 소유한 쿠폰을 찾을 수 없습니다");
        
        // 쿠폰 적용 성공 이벤트는 발행되지 않아야 함
        assertThat(applicationEvents.stream(CouponEvent.CouponApplied.class)).isEmpty();
    }

    @Test
    @DisplayName("이미 사용된 쿠폰으로 주문하면 쿠폰 적용 실패 이벤트가 발행된다")
    void handleOrderCreated_publishesFailedEvent_whenCouponAlreadyUsed() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Coupon coupon = createAndSaveCoupon("USED_COUPON", CouponType.FIXED_AMOUNT, 5_000);
        UserCoupon userCoupon = createAndSaveUserCoupon(user.getId(), coupon);
        userCoupon.use(); // 이미 사용 처리
        userCouponRepository.save(userCoupon);

        OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
            1L,
            user.getId(),
            "USED_COUPON",
            10_000,
            0L,
            List.of(),
            LocalDateTime.now()
        );

        // act
        // CouponEventListener를 통해 호출하여 실제 운영 환경과 동일한 방식으로 테스트
        // CouponEventListener는 @Async와 @TransactionalEventListener(phase = AFTER_COMMIT)로 설정되어 있지만,
        // 테스트에서는 동기적으로 실행되도록 설정되어 있을 수 있음
        couponEventListener.handleOrderCreated(event);
        
        // 비동기 처리 대기
        Thread.sleep(100);

        // assert
        // 쿠폰 적용 실패 이벤트가 발행되었는지 확인
        assertThat(applicationEvents.stream(CouponEvent.CouponApplicationFailed.class)).hasSize(1);
        CouponEvent.CouponApplicationFailed failedEvent = applicationEvents.stream(CouponEvent.CouponApplicationFailed.class)
                .findFirst()
                .orElseThrow();
        assertThat(failedEvent.orderId()).isEqualTo(1L);
        assertThat(failedEvent.userId()).isEqualTo(user.getId());
        assertThat(failedEvent.couponCode()).isEqualTo("USED_COUPON");
        assertThat(failedEvent.failureReason()).contains("이미 사용된 쿠폰입니다");
        
        // 쿠폰 적용 성공 이벤트는 발행되지 않아야 함
        assertThat(applicationEvents.stream(CouponEvent.CouponApplied.class)).isEmpty();
    }
}
