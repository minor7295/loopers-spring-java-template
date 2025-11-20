package com.loopers.domain.coupon;

import java.util.Optional;

/**
 * UserCoupon 엔티티에 대한 저장소 인터페이스.
 * <p>
 * 사용자 쿠폰 정보의 영속성 계층과의 상호작용을 정의합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface UserCouponRepository {
    /**
     * 사용자 쿠폰을 저장합니다.
     *
     * @param userCoupon 저장할 사용자 쿠폰
     * @return 저장된 사용자 쿠폰
     */
    UserCoupon save(UserCoupon userCoupon);

    /**
     * 사용자 ID와 쿠폰 코드로 사용자 쿠폰을 조회합니다.
     *
     * @param userId 사용자 ID
     * @param couponCode 쿠폰 코드
     * @return 조회된 사용자 쿠폰을 담은 Optional
     */
    Optional<UserCoupon> findByUserIdAndCouponCode(Long userId, String couponCode);

    /**
     * 사용자 ID와 쿠폰 코드로 사용자 쿠폰을 조회합니다. (비관적 락)
     * <p>
     * 동시성 제어가 필요한 경우 사용합니다. (예: 쿠폰 사용)
     * </p>
     * <p>
     * <b>Lock 전략:</b>
     * <ul>
     *   <li><b>PESSIMISTIC_WRITE:</b> SELECT ... FOR UPDATE 사용</li>
     *   <li><b>Lock 범위:</b> UNIQUE(userId, couponId) 인덱스 기반 조회로 해당 행만 락 (최소화)</li>
     *   <li><b>사용 목적:</b> 쿠폰 사용 시 Lost Update 방지</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID
     * @param couponCode 쿠폰 코드
     * @return 조회된 사용자 쿠폰을 담은 Optional
     */
    Optional<UserCoupon> findByUserIdAndCouponCodeForUpdate(Long userId, String couponCode);
}

