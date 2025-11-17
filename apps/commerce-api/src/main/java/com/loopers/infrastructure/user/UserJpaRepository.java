package com.loopers.infrastructure.user;

import com.loopers.domain.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * User 엔티티를 위한 Spring Data JPA 리포지토리.
 * <p>
 * JpaRepository를 확장하여 기본 CRUD 기능과 
 * 사용자 ID 기반 조회 기능을 제공합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface UserJpaRepository extends JpaRepository<User, Long> {
    /**
     * 사용자 ID로 사용자를 조회합니다.
     *
     * @param userId 조회할 사용자 ID
     * @return 조회된 사용자를 담은 Optional
     */
    Optional<User> findByUserId(String userId);

    /**
     * 사용자 ID로 사용자를 조회합니다. (비관적 락)
     * <p>
     * SELECT ... FOR UPDATE를 사용하여 동시성 제어를 보장합니다.
     * </p>
     *
     * @param userId 조회할 사용자 ID
     * @return 조회된 사용자를 담은 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.userId = :userId")
    Optional<User> findByUserIdForUpdate(@Param("userId") String userId);
}
