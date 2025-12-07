package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PaymentTest {

    @DisplayName("필수 입력값 검증")
    @Nested
    class InputValidation {
        @DisplayName("결제 생성 시 주문 ID가 null이면 예외가 발생한다.")
        @Test
        void throwsException_whenOrderIdIsNull() {
            // arrange
            Long userId = PaymentTestFixture.ValidPayment.USER_ID;
            Long amount = PaymentTestFixture.ValidPayment.AMOUNT;

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Payment.of(null, userId, PaymentTestFixture.ValidPayment.CARD_TYPE, PaymentTestFixture.ValidPayment.CARD_NO, amount, PaymentTestFixture.ValidPayment.REQUESTED_AT);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("결제 생성 시 결제 금액이 0 이하이면 예외가 발생한다.")
        @Test
        void throwsException_whenAmountIsNotPositive() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            Long userId = PaymentTestFixture.ValidPayment.USER_ID;
            Long invalidAmount = PaymentTestFixture.InvalidPayment.INVALID_AMOUNT;

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Payment.of(orderId, userId, PaymentTestFixture.ValidPayment.CARD_TYPE, PaymentTestFixture.ValidPayment.CARD_NO, invalidAmount, PaymentTestFixture.ValidPayment.REQUESTED_AT);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("상태 검증")
    @Nested
    class StatusValidation {
        @DisplayName("포인트로 전액 결제하면 SUCCESS 상태로 생성된다.")
        @Test
        void hasSuccessStatus_whenPointCoversTotalAmount() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            Long userId = PaymentTestFixture.ValidPayment.USER_ID;
            Long totalAmount = PaymentTestFixture.ValidPayment.AMOUNT;
            Long usedPoint = totalAmount; // 포인트로 전액 결제

            // act
            Payment payment = Payment.of(orderId, userId, totalAmount, usedPoint, PaymentTestFixture.ValidPayment.REQUESTED_AT);

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getUsedPoint()).isEqualTo(usedPoint);
            assertThat(payment.getPaidAmount()).isEqualTo(0L);
        }

        @DisplayName("포인트로 결제하지 않으면 PENDING 상태로 생성된다.")
        @Test
        void hasPendingStatus_whenPointDoesNotCoverTotalAmount() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            Long userId = PaymentTestFixture.ValidPayment.USER_ID;
            Long totalAmount = PaymentTestFixture.ValidPayment.AMOUNT;
            Long usedPoint = 0L; // 포인트 미사용

            // act
            Payment payment = Payment.of(orderId, userId, totalAmount, usedPoint, PaymentTestFixture.ValidPayment.REQUESTED_AT);

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getUsedPoint()).isEqualTo(usedPoint);
            assertThat(payment.getPaidAmount()).isEqualTo(totalAmount);
        }

        @DisplayName("포인트로 부분 결제하면 PENDING 상태로 생성된다.")
        @Test
        void hasPendingStatus_whenPointPartiallyCoversTotalAmount() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            Long userId = PaymentTestFixture.ValidPayment.USER_ID;
            Long totalAmount = PaymentTestFixture.ValidPayment.AMOUNT;
            Long usedPoint = totalAmount / 2; // 포인트로 절반 결제

            // act
            Payment payment = Payment.of(orderId, userId, totalAmount, usedPoint, PaymentTestFixture.ValidPayment.REQUESTED_AT);

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getUsedPoint()).isEqualTo(usedPoint);
            assertThat(payment.getPaidAmount()).isEqualTo(totalAmount - usedPoint);
        }

        @DisplayName("결제는 PENDING 상태에서 SUCCESS 상태로 전이할 수 있다.")
        @Test
        void canTransitionToSuccess_whenPending() {
            // arrange
            Payment payment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            LocalDateTime completedAt = LocalDateTime.of(2025, 12, 1, 10, 5, 0);

            // act
            payment.toSuccess(completedAt);

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getPgCompletedAt()).isEqualTo(completedAt);
        }

        @DisplayName("결제는 PENDING 상태에서 FAILED 상태로 전이할 수 있다.")
        @Test
        void canTransitionToFailed_whenPending() {
            // arrange
            Payment payment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            LocalDateTime completedAt = LocalDateTime.of(2025, 12, 1, 10, 5, 0);
            String failureReason = "카드 한도 초과";

            // act
            payment.toFailed(failureReason, completedAt);

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo(failureReason);
            assertThat(payment.getPgCompletedAt()).isEqualTo(completedAt);
        }

        @DisplayName("FAILED 상태에서 SUCCESS로 전이할 수 없다.")
        @Test
        void throwsException_whenTransitioningToSuccessFromFailed() {
            // arrange
            Payment payment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            payment.toFailed("실패 사유", LocalDateTime.now());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                payment.toSuccess(LocalDateTime.now());
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("SUCCESS 상태에서 FAILED로 전이할 수 없다.")
        @Test
        void throwsException_whenTransitioningToFailedFromSuccess() {
            // arrange
            Payment payment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            payment.toSuccess(LocalDateTime.now());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                payment.toFailed("실패 사유", LocalDateTime.now());
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("완료된 결제는 isCompleted가 true를 반환한다.")
        @Test
        void returnsTrue_whenPaymentIsCompleted() {
            // arrange
            Payment successPayment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            successPayment.toSuccess(LocalDateTime.now());

            Payment failedPayment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            failedPayment.toFailed("ERROR", LocalDateTime.now());

            Payment pendingPayment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );

            // assert
            assertThat(successPayment.isCompleted()).isTrue();
            assertThat(failedPayment.isCompleted()).isTrue();
            assertThat(pendingPayment.isCompleted()).isFalse();
        }

        @DisplayName("이미 SUCCESS 상태인 결제를 다시 SUCCESS로 전이해도 예외가 발생하지 않는다.")
        @Test
        void doesNotThrowException_whenTransitioningToSuccessFromSuccess() {
            // arrange
            Payment payment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            LocalDateTime firstCompletedAt = LocalDateTime.of(2025, 12, 1, 10, 5, 0);
            LocalDateTime secondCompletedAt = LocalDateTime.of(2025, 12, 1, 10, 6, 0);
            payment.toSuccess(firstCompletedAt);

            // act
            payment.toSuccess(secondCompletedAt); // 멱등성: 이미 SUCCESS 상태면 아무 작업도 하지 않음

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getPgCompletedAt()).isEqualTo(firstCompletedAt); // 첫 번째 시각 유지
        }

        @DisplayName("이미 FAILED 상태인 결제를 다시 FAILED로 전이해도 예외가 발생하지 않는다.")
        @Test
        void doesNotThrowException_whenTransitioningToFailedFromFailed() {
            // arrange
            Payment payment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            LocalDateTime firstCompletedAt = LocalDateTime.of(2025, 12, 1, 10, 5, 0);
            LocalDateTime secondCompletedAt = LocalDateTime.of(2025, 12, 1, 10, 6, 0);
            String firstReason = "첫 번째 실패 사유";
            String secondReason = "두 번째 실패 사유";
            payment.toFailed(firstReason, firstCompletedAt);

            // act
            payment.toFailed(secondReason, secondCompletedAt); // 멱등성: 이미 FAILED 상태면 아무 작업도 하지 않음

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo(firstReason); // 첫 번째 사유 유지
            assertThat(payment.getPgCompletedAt()).isEqualTo(firstCompletedAt); // 첫 번째 시각 유지
        }
    }
}
