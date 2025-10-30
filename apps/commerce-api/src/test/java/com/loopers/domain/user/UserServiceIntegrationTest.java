package com.loopers.domain.user;

import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
public class UserServiceIntegrationTest {
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

    @DisplayName("회원 가입에 관한 단위 테스트")
    @Nested
    class SignUp {
        @DisplayName("회원가입시 User 저장이 수행된다.")
        @Test
        void returnsExampleInfo_whenValidIdIsProvided() {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;

            // act
            User user = userService.signUp(userId, email, birthDate);

            // assert
            assertAll(
                () -> assertThat(user).isNotNull(),
                () -> assertThat(user.getUserId()).isEqualTo(userId),
                () -> verify(userJpaRepository, times(1)).save(any(User.class))
            );
        }

        @DisplayName("이미 가입된 ID로 회원가입 시도 시, 실패한다.")
        @Test
        void fails_whenDuplicateUserIdExists() {
            // arrange
            String userId = UserTestFixture.ValidUser.USER_ID;
            String email = UserTestFixture.ValidUser.EMAIL;
            String birthDate = UserTestFixture.ValidUser.BIRTH_DATE;
            userService.signUp(userId, email, birthDate);

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                userService.signUp(userId, email, birthDate)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }
}
