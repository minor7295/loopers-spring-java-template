package com.loopers.domain.point;

import com.loopers.application.signup.SignUpFacade;
import com.loopers.domain.user.Gender;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
public class PointServiceIntegrationTest {
    @Autowired
    private PointService pointService;

    @Autowired
    private SignUpFacade signUpFacade;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("포인트 조회에 관한 통합 테스트")
    @Nested
    class PointsLookup {
        @DisplayName("해당 ID 의 회원이 존재할 경우, 보유 포인트가 반환된다.")
        @Test
        void returnsPoints_whenUserExists() {
            // arrange
            String userId = "testuser";
            String email = "test@example.com";
            String birthDate = "1990-01-01";
            Gender gender = Gender.MALE;
            Long balance = 0L;
            signUpFacade.signUp(userId, email, birthDate, gender);

            // act
            Point point = pointService.findByUserId(userId);

            // assert
            assertAll(
                () -> assertThat(point).isNotNull(),
                () -> assertThat(point.getUser().getUserId()).isEqualTo(userId),
                () -> assertThat(point.getBalance()).isEqualTo(balance)
            );
        }

        @DisplayName("해당 ID 의 회원이 존재하지 않을 경우, null 이 반환된다.")
        @Test
        void returnsNull_whenUserDoesNotExist() {
            // arrange
            String userId = "unknown";

            // act
            Point found = pointService.findByUserId(userId);

            // assert
            assertThat(found).isNull();
        }
    }
}


