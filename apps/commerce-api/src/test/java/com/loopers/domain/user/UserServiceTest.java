package com.loopers.domain.user;

import com.loopers.application.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UserService 테스트.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @DisplayName("사용자 조회")
    @Nested
    class FindUser {
        @DisplayName("사용자 ID로 사용자를 조회할 수 있다.")
        @Test
        void findsUserByUserId() {
            // arrange
            String userId = "testuser";
            User expectedUser = User.of(userId, "test@example.com", "1990-01-01", Gender.MALE, Point.of(1000L));
            when(userRepository.findByUserId(userId)).thenReturn(expectedUser);

            // act
            User result = userService.getUser(userId);

            // assert
            assertThat(result).isEqualTo(expectedUser);
            verify(userRepository, times(1)).findByUserId(userId);
        }

        @DisplayName("사용자를 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenUserNotFound() {
            // arrange
            String userId = "unknown";
            when(userRepository.findByUserId(userId)).thenReturn(null);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                userService.getUser(userId);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(result.getMessage()).contains("사용자를 찾을 수 없습니다");
            verify(userRepository, times(1)).findByUserId(userId);
        }
    }

    @DisplayName("사용자 조회 (비관적 락)")
    @Nested
    class FindUserForUpdate {
        @DisplayName("사용자 ID로 사용자를 조회할 수 있다. (비관적 락)")
        @Test
        void findsUserByUserIdForUpdate() {
            // arrange
            String userId = "testuser";
            User expectedUser = User.of(userId, "test@example.com", "1990-01-01", Gender.MALE, Point.of(1000L));
            when(userRepository.findByUserIdForUpdate(userId)).thenReturn(expectedUser);

            // act
            User result = userService.getUserForUpdate(userId);

            // assert
            assertThat(result).isEqualTo(expectedUser);
            verify(userRepository, times(1)).findByUserIdForUpdate(userId);
        }

        @DisplayName("사용자를 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenUserNotFound() {
            // arrange
            String userId = "unknown";
            when(userRepository.findByUserIdForUpdate(userId)).thenReturn(null);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                userService.getUserForUpdate(userId);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(result.getMessage()).contains("사용자를 찾을 수 없습니다");
            verify(userRepository, times(1)).findByUserIdForUpdate(userId);
        }
    }

    @DisplayName("사용자 조회 (ID)")
    @Nested
    class FindUserById {
        @DisplayName("사용자 ID (PK)로 사용자를 조회할 수 있다.")
        @Test
        void findsUserById() {
            // arrange
            Long id = 1L;
            User expectedUser = User.of("testuser", "test@example.com", "1990-01-01", Gender.MALE, Point.of(1000L));
            when(userRepository.findById(id)).thenReturn(expectedUser);

            // act
            User result = userService.getUserById(id);

            // assert
            assertThat(result).isEqualTo(expectedUser);
            verify(userRepository, times(1)).findById(id);
        }

        @DisplayName("사용자를 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenUserNotFound() {
            // arrange
            Long id = 999L;
            when(userRepository.findById(id)).thenReturn(null);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                userService.getUserById(id);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(result.getMessage()).contains("사용자를 찾을 수 없습니다");
            verify(userRepository, times(1)).findById(id);
        }
    }

    @DisplayName("사용자 저장")
    @Nested
    class SaveUser {
        @DisplayName("사용자를 저장할 수 있다.")
        @Test
        void savesUser() {
            // arrange
            User user = User.of("testuser", "test@example.com", "1990-01-01", Gender.MALE, Point.of(1000L));
            when(userRepository.save(any(User.class))).thenReturn(user);

            // act
            User result = userService.save(user);

            // assert
            assertThat(result).isEqualTo(user);
            verify(userRepository, times(1)).save(user);
        }
    }
}

