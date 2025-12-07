package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PaymentService 테스트.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService")
public class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentService paymentService;

    @DisplayName("결제 생성")
    @Nested
    class CreatePayment {
        @DisplayName("카드 결제를 생성할 수 있다.")
        @Test
        void createsCardPayment() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            Long userId = PaymentTestFixture.ValidPayment.USER_ID;
            CardType cardType = PaymentTestFixture.ValidPayment.CARD_TYPE;
            String cardNo = PaymentTestFixture.ValidPayment.CARD_NO;
            Long amount = PaymentTestFixture.ValidPayment.AMOUNT;
            LocalDateTime requestedAt = PaymentTestFixture.ValidPayment.REQUESTED_AT;

            Payment expectedPayment = Payment.of(orderId, userId, cardType, cardNo, amount, requestedAt);
            when(paymentRepository.save(any(Payment.class))).thenReturn(expectedPayment);

            // act
            Payment result = paymentService.create(orderId, userId, cardType, cardNo, amount, requestedAt);

            // assert
            assertThat(result).isNotNull();
            verify(paymentRepository, times(1)).save(any(Payment.class));
        }

        @DisplayName("포인트 결제를 생성할 수 있다.")
        @Test
        void createsPointPayment() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            Long userId = PaymentTestFixture.ValidPayment.USER_ID;
            Long totalAmount = PaymentTestFixture.ValidPayment.AMOUNT;
            Long usedPoint = PaymentTestFixture.ValidPayment.ZERO_POINT;
            LocalDateTime requestedAt = PaymentTestFixture.ValidPayment.REQUESTED_AT;

            Payment expectedPayment = Payment.of(orderId, userId, totalAmount, usedPoint, requestedAt);
            when(paymentRepository.save(any(Payment.class))).thenReturn(expectedPayment);

            // act
            Payment result = paymentService.create(orderId, userId, totalAmount, usedPoint, requestedAt);

            // assert
            assertThat(result).isNotNull();
            verify(paymentRepository, times(1)).save(any(Payment.class));
        }
    }

    @DisplayName("결제 상태 변경")
    @Nested
    class UpdatePaymentStatus {
        @DisplayName("결제를 SUCCESS 상태로 전이할 수 있다.")
        @Test
        void transitionsToSuccess() {
            // arrange
            Long paymentId = 1L;
            Payment payment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            LocalDateTime completedAt = LocalDateTime.of(2025, 12, 1, 10, 5, 0);

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);

            // act
            paymentService.toSuccess(paymentId, completedAt);

            // assert
            verify(paymentRepository, times(1)).findById(paymentId);
            verify(paymentRepository, times(1)).save(payment);
            // 상태 변경 검증은 PaymentTest에서 이미 검증했으므로 제거
        }

        @DisplayName("결제를 FAILED 상태로 전이할 수 있다.")
        @Test
        void transitionsToFailed() {
            // arrange
            Long paymentId = 1L;
            Payment payment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            LocalDateTime completedAt = LocalDateTime.of(2025, 12, 1, 10, 5, 0);
            String failureReason = "카드 한도 초과";

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);

            // act
            paymentService.toFailed(paymentId, failureReason, completedAt);

            // assert
            verify(paymentRepository, times(1)).findById(paymentId);
            verify(paymentRepository, times(1)).save(payment);
            // 상태 변경 검증은 PaymentTest에서 이미 검증했으므로 제거
        }

        @DisplayName("결제를 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenPaymentNotFound() {
            // arrange
            Long paymentId = 999L;
            LocalDateTime completedAt = LocalDateTime.now();

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentService.toSuccess(paymentId, completedAt);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(paymentRepository, times(1)).findById(paymentId);
            verify(paymentRepository, never()).save(any(Payment.class));
        }
    }

    @DisplayName("결제 조회")
    @Nested
    class FindPayment {
        @DisplayName("결제 ID로 결제를 조회할 수 있다.")
        @Test
        void findsById() {
            // arrange
            Long paymentId = 1L;
            Payment expectedPayment = Payment.of(
                PaymentTestFixture.ValidPayment.ORDER_ID,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(expectedPayment));

            // act
            Payment result = paymentService.findById(paymentId);

            // assert
            assertThat(result).isEqualTo(expectedPayment);
            verify(paymentRepository, times(1)).findById(paymentId);
        }

        @DisplayName("주문 ID로 결제를 조회할 수 있다.")
        @Test
        void findsByOrderId() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            Payment expectedPayment = Payment.of(
                orderId,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.ZERO_POINT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(expectedPayment));

            // act
            Optional<Payment> result = paymentService.findByOrderId(orderId);

            // assert
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(expectedPayment);
            verify(paymentRepository, times(1)).findByOrderId(orderId);
        }

        @DisplayName("결제를 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenPaymentNotFound() {
            // arrange
            Long paymentId = 999L;

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentService.findById(paymentId);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(paymentRepository, times(1)).findById(paymentId);
        }
    }
}

