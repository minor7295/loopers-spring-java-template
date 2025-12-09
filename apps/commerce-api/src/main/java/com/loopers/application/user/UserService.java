package com.loopers.application.user;

import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 애플리케이션 서비스.
 * <p>
 * 사용자 생성, 조회, 포인트 관리 등의 애플리케이션 로직을 처리합니다.
 * Repository에 의존하며 트랜잭션 관리 및 데이터 무결성 제약 조건을 처리합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@RequiredArgsConstructor
@Component
public class UserService {
    private final UserRepository userRepository;

    /**
     * 새로운 사용자를 생성합니다.
     *
     * @param userId 사용자 ID
     * @param email 이메일 주소
     * @param birthDateStr 생년월일 (yyyy-MM-dd)
     * @param gender 성별
     * @return 생성된 사용자
     * @throws CoreException 중복된 사용자 ID가 존재하거나 유효성 검증 실패 시
     */
    public User create(String userId, String email, String birthDateStr, Gender gender, Point point) {
        User user = User.of(userId, email, birthDateStr, gender, point);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage() != null && e.getMessage().contains("user_id")) {
                throw new CoreException(ErrorType.CONFLICT, "이미 가입된 ID입니다: " + userId);
            }
            throw new CoreException(ErrorType.CONFLICT, "데이터 무결성 제약 조건 위반");
        }
    }

    /**
     * 사용자 ID로 사용자를 조회합니다.
     *
     * @param userId 조회할 사용자 ID
     * @return 조회된 사용자
     * @throws CoreException 사용자를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public User getUser(String userId) {
        User user = userRepository.findByUserId(userId);
        if (user == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return user;
    }

    /**
     * 사용자 ID로 사용자를 조회합니다. (비관적 락)
     * <p>
     * 포인트 차감 등 동시성 제어가 필요한 경우 사용합니다.
     * </p>
     *
     * @param userId 조회할 사용자 ID
     * @return 조회된 사용자
     * @throws CoreException 사용자를 찾을 수 없는 경우
     */
    @Transactional
    public User getUserForUpdate(String userId) {
        User user = userRepository.findByUserIdForUpdate(userId);
        if (user == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return user;
    }

    /**
     * 사용자 ID (PK)로 사용자를 조회합니다.
     *
     * @param id 사용자 ID (PK)
     * @return 조회된 사용자
     * @throws CoreException 사용자를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public User getUserById(Long id) {
        User user = userRepository.findById(id);
        if (user == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return user;
    }

    /**
     * 사용자를 저장합니다.
     *
     * @param user 저장할 사용자
     * @return 저장된 사용자
     */
    @Transactional
    public User save(User user) {
        return userRepository.save(user);
    }

    /**
     * 사용자의 포인트를 조회합니다.
     *
     * @param userId 조회할 사용자 ID
     * @return 포인트 정보
     * @throws CoreException 사용자를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public PointsInfo getPoints(String userId) {
        User user = getUser(userId);
        return PointsInfo.from(user);
    }

    /**
     * 사용자의 포인트를 충전합니다.
     * <p>
     * 트랜잭션 내에서 실행되어 데이터 일관성을 보장합니다.
     * </p>
     *
     * @param userId 충전할 사용자 ID
     * @param amount 충전할 포인트 금액
     * @return 충전된 포인트 정보
     * @throws CoreException 사용자를 찾을 수 없거나 충전 금액이 유효하지 않은 경우
     */
    @Transactional
    public PointsInfo chargePoint(String userId, Long amount) {
        User user = getUser(userId);
        Point point = Point.of(amount);
        user.receivePoint(point);
        User savedUser = save(user);
        return PointsInfo.from(savedUser);
    }

    /**
     * 포인트 정보를 담는 레코드.
     *
     * @param userId 사용자 ID
     * @param balance 포인트 잔액
     */
    public record PointsInfo(String userId, Long balance) {
        /**
         * User 엔티티로부터 PointsInfo를 생성합니다.
         *
         * @param user 사용자 엔티티
         * @return 생성된 PointsInfo
         */
        public static PointsInfo from(User user) {
            return new PointsInfo(user.getUserId(), user.getPoint().getValue());
        }
    }
}
