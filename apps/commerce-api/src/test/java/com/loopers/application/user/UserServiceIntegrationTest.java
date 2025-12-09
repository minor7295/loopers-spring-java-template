package com.loopers.application.user;

import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserTestFixture;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@DisplayName("UserService 통합 테스트")
class UserServiceIntegrationTest {
    @Autowired
    private UserService userService;

    @MockitoSpyBean
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /**
     * 테스트용 사용자를 생성합니다.
     */
    private void createUser(String userId, String email, String birthDate, Gender gender) {
        userService.create(userId, email, birthDate, gender, Point.of(0L));
    }

    @DisplayName("회원 가입에 관한 통합 테스트")
    @Nested
    class SignUp {
        @DisplayName("회원가입시 User 저장이 수행된다.")
        @ParameterizedTest
        @EnumSource(Gender.class)
        void createsUser_whenValidIdIsProvided(Gender gender) {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;
            Mockito.reset(userJpaRepository);

            // act
            User user = userService.create(userId, email, birthDate, gender, Point.of(0L));

            // assert
            assertAll(
                () -> assertThat(user).isNotNull(),
                () -> assertThat(user.getUserId()).isEqualTo(userId),
                () -> verify(userJpaRepository, times(1)).save(any(User.class))
            );
        }

        @DisplayName("이미 가입된 ID로 회원가입 시도 시, 실패한다.")
        @ParameterizedTest
        @EnumSource(Gender.class)
        void fails_whenDuplicateUserIdExists(Gender gender) {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;
            userService.create(userId, email, birthDate, gender, Point.of(0L));

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                userService.create(userId, email, birthDate, gender, Point.of(0L))
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @DisplayName("포인트 조회에 관한 통합 테스트")
    @Nested
    class PointInfo {
        @DisplayName("해당 ID 의 회원이 존재할 경우, 보유 포인트가 반환된다.")
        @ParameterizedTest
        @EnumSource(Gender.class)
        void returnsPoints_whenUserExists(Gender gender) {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;
            createUser(userId, email, birthDate, gender);

            // act
            UserService.PointsInfo pointsInfo = userService.getPoints(userId);

            // assert
            assertAll(
                () -> assertThat(pointsInfo).isNotNull(),
                () -> assertThat(pointsInfo.userId()).isEqualTo(userId),
                () -> assertThat(pointsInfo.balance()).isEqualTo(0L)
            );
        }

        @DisplayName("해당 ID 의 회원이 존재하지 않을 경우, 예외가 발생한다.")
        @Test
        void throwsException_whenUserDoesNotExist() {
            // arrange
            String userId = "unknown";

            // act & assert
            assertThatThrownBy(() -> userService.getPoints(userId))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("포인트 충전에 관한 통합 테스트")
    @Nested
    class PointCharge {
        @DisplayName("포인트를 충전할 수 있다.")
        @ParameterizedTest
        @EnumSource(Gender.class)
        void chargesPoints_success(Gender gender) {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;
            createUser(userId, email, birthDate, gender);
            Long chargeAmount = 10_000L;

            // act
            UserService.PointsInfo pointsInfo = userService.chargePoint(userId, chargeAmount);

            // assert
            assertAll(
                () -> assertThat(pointsInfo).isNotNull(),
                () -> assertThat(pointsInfo.userId()).isEqualTo(userId),
                () -> assertThat(pointsInfo.balance()).isEqualTo(chargeAmount)
            );
        }

        @DisplayName("사용자가 존재하지 않으면 포인트 충전 시 예외가 발생한다.")
        @Test
        void throwsException_whenUserDoesNotExist() {
            // arrange
            String userId = "unknown";
            Long chargeAmount = 10_000L;

            // act & assert
            assertThatThrownBy(() -> userService.chargePoint(userId, chargeAmount))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.NOT_FOUND);
        }
    }
}
