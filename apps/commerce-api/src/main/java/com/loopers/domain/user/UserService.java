package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class UserService {
    private final UserRepository userRepository;

    @Transactional
    public User signUp(String userId, String email, String birthDateStr) {
        User user = User.of(userId, email, birthDateStr);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(ErrorType.CONFLICT, "이미 가입된 ID입니다: " + userId);
        }
    }
}
