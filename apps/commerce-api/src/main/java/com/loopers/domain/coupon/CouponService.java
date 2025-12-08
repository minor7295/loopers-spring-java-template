package com.loopers.domain.coupon;

import com.loopers.domain.coupon.discount.CouponDiscountStrategyFactory;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 도메인 서비스.
 * <p>
 * 쿠폰 조회, 사용 등의 도메인 로직을 처리합니다.
 * Repository에 의존하며 비즈니스 규칙을 캡슐화합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@RequiredArgsConstructor
@Component
public class CouponService {
    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponDiscountStrategyFactory couponDiscountStrategyFactory;

    /**
     * 쿠폰을 적용하여 할인 금액을 계산하고 쿠폰을 사용 처리합니다.
     * <p>
     * <b>동시성 제어 전략:</b>
     * <ul>
     *   <li><b>OPTIMISTIC_LOCK 사용 근거:</b> 쿠폰 중복 사용 방지, Hot Spot 대응</li>
     *   <li><b>@Version 필드:</b> UserCoupon 엔티티의 version 필드를 통해 자동으로 낙관적 락 적용</li>
     *   <li><b>동시 사용 시:</b> 한 명만 성공하고 나머지는 OptimisticLockException 발생</li>
     *   <li><b>사용 목적:</b> 동일 쿠폰으로 여러 기기에서 동시 주문해도 한 번만 사용되도록 보장</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID
     * @param couponCode 쿠폰 코드
     * @param subtotal 주문 소계 금액
     * @return 할인 금액
     * @throws CoreException 쿠폰을 찾을 수 없거나 사용 불가능한 경우, 동시 사용으로 인한 충돌 시
     */
    @Transactional
    public Integer applyCoupon(Long userId, String couponCode, Integer subtotal) {
        // 쿠폰 존재 여부 확인
        Coupon coupon = couponRepository.findByCode(couponCode)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                String.format("쿠폰을 찾을 수 없습니다. (쿠폰 코드: %s)", couponCode)));

        // 낙관적 락을 사용하여 사용자 쿠폰 조회 (동시성 제어)
        // @Version 필드가 있어 자동으로 낙관적 락이 적용됨
        UserCoupon userCoupon = userCouponRepository.findByUserIdAndCouponCodeForUpdate(userId, couponCode)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                String.format("사용자가 소유한 쿠폰을 찾을 수 없습니다. (쿠폰 코드: %s)", couponCode)));

        // 쿠폰 사용 가능 여부 확인
        if (!userCoupon.isAvailable()) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                String.format("이미 사용된 쿠폰입니다. (쿠폰 코드: %s)", couponCode));
        }

        // 쿠폰 사용 처리
        userCoupon.use();

        // 할인 금액 계산 (전략 패턴 사용)
        Integer discountAmount = coupon.calculateDiscountAmount(subtotal, couponDiscountStrategyFactory);

        try {
            // 사용자 쿠폰 저장 (version 체크 자동 수행)
            // 다른 트랜잭션이 먼저 수정했다면 OptimisticLockException 발생
            userCouponRepository.save(userCoupon);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 낙관적 락 충돌: 다른 트랜잭션이 먼저 쿠폰을 사용함
            throw new CoreException(ErrorType.CONFLICT,
                String.format("쿠폰이 이미 사용되었습니다. (쿠폰 코드: %s)", couponCode));
        }

        return discountAmount;
    }

    /**
     * 쿠폰 할인 금액만 계산합니다 (쿠폰 사용 처리는 하지 않음).
     * <p>
     * 주문 생성 시 할인 금액을 계산하기 위해 사용됩니다.
     * 실제 쿠폰 사용 처리는 이벤트 리스너에서 별도로 처리됩니다.
     * </p>
     *
     * @param couponCode 쿠폰 코드
     * @param subtotal 주문 소계 금액
     * @return 할인 금액
     * @throws CoreException 쿠폰을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public Integer calculateDiscountAmount(String couponCode, Integer subtotal) {
        // 쿠폰 존재 여부 확인
        Coupon coupon = couponRepository.findByCode(couponCode)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                String.format("쿠폰을 찾을 수 없습니다. (쿠폰 코드: %s)", couponCode)));

        // 할인 금액 계산 (전략 패턴 사용)
        return coupon.calculateDiscountAmount(subtotal, couponDiscountStrategyFactory);
    }
}

