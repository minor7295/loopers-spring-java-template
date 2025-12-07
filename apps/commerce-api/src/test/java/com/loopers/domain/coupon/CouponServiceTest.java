package com.loopers.domain.coupon;

import com.loopers.domain.coupon.discount.CouponDiscountStrategy;
import com.loopers.domain.coupon.discount.CouponDiscountStrategyFactory;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * CouponService 테스트.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CouponService")
public class CouponServiceTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private UserCouponRepository userCouponRepository;

    @Mock
    private CouponDiscountStrategyFactory couponDiscountStrategyFactory;

    @Mock
    private CouponDiscountStrategy couponDiscountStrategy;

    @InjectMocks
    private CouponService couponService;

    @DisplayName("쿠폰 적용")
    @Nested
    class ApplyCoupon {
        @DisplayName("쿠폰을 적용하여 할인 금액을 계산하고 쿠폰을 사용 처리할 수 있다.")
        @Test
        void appliesCouponAndCalculatesDiscount() {
            // arrange
            Long userId = 1L;
            String couponCode = "FIXED5000";
            Integer subtotal = 10_000;
            Integer expectedDiscount = 5_000;

            Coupon coupon = Coupon.of(couponCode, CouponType.FIXED_AMOUNT, 5_000);
            UserCoupon userCoupon = UserCoupon.of(userId, coupon);

            when(couponRepository.findByCode(couponCode)).thenReturn(Optional.of(coupon));
            when(userCouponRepository.findByUserIdAndCouponCodeForUpdate(userId, couponCode))
                .thenReturn(Optional.of(userCoupon));
            when(couponDiscountStrategyFactory.getStrategy(CouponType.FIXED_AMOUNT))
                .thenReturn(couponDiscountStrategy);
            when(couponDiscountStrategy.calculateDiscountAmount(subtotal, 5_000))
                .thenReturn(expectedDiscount);
            when(userCouponRepository.save(any(UserCoupon.class))).thenReturn(userCoupon);

            // act
            Integer result = couponService.applyCoupon(userId, couponCode, subtotal);

            // assert
            assertThat(result).isEqualTo(expectedDiscount);
            assertThat(userCoupon.getIsUsed()).isTrue(); // 쿠폰이 사용되었는지 확인
            verify(couponRepository, times(1)).findByCode(couponCode);
            verify(userCouponRepository, times(1)).findByUserIdAndCouponCodeForUpdate(userId, couponCode);
            verify(userCouponRepository, times(1)).save(userCoupon);
        }

        @DisplayName("쿠폰을 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenCouponNotFound() {
            // arrange
            Long userId = 1L;
            String couponCode = "NON_EXISTENT";
            Integer subtotal = 10_000;

            when(couponRepository.findByCode(couponCode)).thenReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                couponService.applyCoupon(userId, couponCode, subtotal);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(result.getMessage()).contains("쿠폰을 찾을 수 없습니다");
            verify(couponRepository, times(1)).findByCode(couponCode);
            verify(userCouponRepository, never()).findByUserIdAndCouponCodeForUpdate(any(), any());
        }

        @DisplayName("사용자가 소유한 쿠폰을 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenUserCouponNotFound() {
            // arrange
            Long userId = 1L;
            String couponCode = "FIXED5000";
            Integer subtotal = 10_000;

            Coupon coupon = Coupon.of(couponCode, CouponType.FIXED_AMOUNT, 5_000);

            when(couponRepository.findByCode(couponCode)).thenReturn(Optional.of(coupon));
            when(userCouponRepository.findByUserIdAndCouponCodeForUpdate(userId, couponCode))
                .thenReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                couponService.applyCoupon(userId, couponCode, subtotal);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(result.getMessage()).contains("사용자가 소유한 쿠폰을 찾을 수 없습니다");
            verify(couponRepository, times(1)).findByCode(couponCode);
            verify(userCouponRepository, times(1)).findByUserIdAndCouponCodeForUpdate(userId, couponCode);
        }

        @DisplayName("이미 사용된 쿠폰이면 예외가 발생한다.")
        @Test
        void throwsException_whenCouponAlreadyUsed() {
            // arrange
            Long userId = 1L;
            String couponCode = "USED_COUPON";
            Integer subtotal = 10_000;

            Coupon coupon = Coupon.of(couponCode, CouponType.FIXED_AMOUNT, 5_000);
            UserCoupon userCoupon = UserCoupon.of(userId, coupon);
            userCoupon.use(); // 이미 사용 처리

            when(couponRepository.findByCode(couponCode)).thenReturn(Optional.of(coupon));
            when(userCouponRepository.findByUserIdAndCouponCodeForUpdate(userId, couponCode))
                .thenReturn(Optional.of(userCoupon));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                couponService.applyCoupon(userId, couponCode, subtotal);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(result.getMessage()).contains("이미 사용된 쿠폰입니다");
            verify(couponRepository, times(1)).findByCode(couponCode);
            verify(userCouponRepository, times(1)).findByUserIdAndCouponCodeForUpdate(userId, couponCode);
            verify(userCouponRepository, never()).save(any(UserCoupon.class));
        }

        @DisplayName("낙관적 락 충돌 시 예외가 발생한다.")
        @Test
        void throwsException_whenOptimisticLockConflict() {
            // arrange
            Long userId = 1L;
            String couponCode = "FIXED5000";
            Integer subtotal = 10_000;

            Coupon coupon = Coupon.of(couponCode, CouponType.FIXED_AMOUNT, 5_000);
            UserCoupon userCoupon = UserCoupon.of(userId, coupon);

            when(couponRepository.findByCode(couponCode)).thenReturn(Optional.of(coupon));
            when(userCouponRepository.findByUserIdAndCouponCodeForUpdate(userId, couponCode))
                .thenReturn(Optional.of(userCoupon));
            // Coupon.calculateDiscountAmount()가 호출될 때 getStrategy()가 호출되므로 stubbing 필요
            when(couponDiscountStrategyFactory.getStrategy(any(CouponType.class)))
                .thenReturn(couponDiscountStrategy);
            when(couponDiscountStrategy.calculateDiscountAmount(anyInt(), anyInt()))
                .thenReturn(5_000);
            when(userCouponRepository.save(any(UserCoupon.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(UserCoupon.class, userCoupon));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                couponService.applyCoupon(userId, couponCode, subtotal);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(result.getMessage()).contains("쿠폰이 이미 사용되었습니다");
            verify(couponRepository, times(1)).findByCode(couponCode);
            verify(userCouponRepository, times(1)).findByUserIdAndCouponCodeForUpdate(userId, couponCode);
            verify(couponDiscountStrategyFactory, times(1)).getStrategy(CouponType.FIXED_AMOUNT);
            verify(couponDiscountStrategy, times(1)).calculateDiscountAmount(subtotal, 5_000);
            verify(userCouponRepository, times(1)).save(userCoupon);
        }
    }
}

