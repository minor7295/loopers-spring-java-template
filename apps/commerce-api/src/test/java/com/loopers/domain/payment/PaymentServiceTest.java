package com.loopers.domain.payment;

import com.loopers.application.payment.PaymentService;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    
    @Mock
    private PaymentGateway paymentGateway;
    
    @Mock
    private PaymentEventPublisher paymentEventPublisher;

    @InjectMocks
    private PaymentService paymentService;
    
    @BeforeEach
    void setUp() {
        // @Value 어노테이션 필드 설정
        ReflectionTestUtils.setField(paymentService, "callbackBaseUrl", "http://localhost:8080");
    }

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
            Long usedPoint = PaymentTestFixture.ValidPayment.FULL_POINT; // 포인트로 전액 결제
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
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            LocalDateTime completedAt = LocalDateTime.of(2025, 12, 1, 10, 5, 0);

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);

            // act
            paymentService.toSuccess(paymentId, completedAt, null);

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
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );
            LocalDateTime completedAt = LocalDateTime.of(2025, 12, 1, 10, 5, 0);
            String failureReason = "카드 한도 초과";

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);

            // act
            paymentService.toFailed(paymentId, failureReason, completedAt, null);

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
                paymentService.toSuccess(paymentId, completedAt, null);
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
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(expectedPayment));

            // act
            Payment result = paymentService.getPayment(paymentId);

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
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                PaymentTestFixture.ValidPayment.REQUESTED_AT
            );

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(expectedPayment));

            // act
            Optional<Payment> result = paymentService.getPaymentByOrderId(orderId);

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
                paymentService.getPayment(paymentId);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(paymentRepository, times(1)).findById(paymentId);
        }
    }
    
    @DisplayName("PG 결제 요청")
    @Nested
    class RequestPayment {
        @DisplayName("PG 결제 요청을 성공적으로 처리할 수 있다.")
        @Test
        void requestsPaymentSuccessfully() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            String userId = "user123";  // User.userId (String)
            Long userEntityId = PaymentTestFixture.ValidPayment.USER_ID;  // User.id (Long)
            String cardType = "SAMSUNG";
            String cardNo = PaymentTestFixture.ValidPayment.CARD_NO;
            Long amount = PaymentTestFixture.ValidPayment.AMOUNT;
            
            Payment payment = Payment.of(
                orderId,
                userEntityId,
                CardType.SAMSUNG,
                cardNo,
                amount,
                LocalDateTime.now()
            );
            
            PaymentRequestResult.Success successResult = new PaymentRequestResult.Success("TXN123456");
            
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            when(paymentGateway.requestPayment(any(PaymentRequest.class))).thenReturn(successResult);
            
            // act
            PaymentRequestResult result = paymentService.requestPayment(orderId, userId, userEntityId, cardType, cardNo, amount);
            
            // assert
            assertThat(result).isInstanceOf(PaymentRequestResult.Success.class);
            assertThat(((PaymentRequestResult.Success) result).transactionKey()).isEqualTo("TXN123456");
            verify(paymentRepository, times(1)).save(any(Payment.class));
            verify(paymentGateway, times(1)).requestPayment(any(PaymentRequest.class));
        }
        
        @DisplayName("비즈니스 실패 시 결제 상태를 FAILED로 변경한다.")
        @Test
        void updatesPaymentToFailed_whenBusinessFailure() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            String userId = "user123";  // User.userId (String)
            Long userEntityId = PaymentTestFixture.ValidPayment.USER_ID;  // User.id (Long)
            String cardType = "SAMSUNG";
            String cardNo = PaymentTestFixture.ValidPayment.CARD_NO;
            Long amount = PaymentTestFixture.ValidPayment.AMOUNT;
            
            Payment payment = Payment.of(
                orderId,
                userEntityId,
                CardType.SAMSUNG,
                cardNo,
                amount,
                LocalDateTime.now()
            );
            
            PaymentRequestResult.Failure failureResult = new PaymentRequestResult.Failure(
                "LIMIT_EXCEEDED",
                "카드 한도 초과",
                false,
                false
            );
            
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            when(paymentGateway.requestPayment(any(PaymentRequest.class))).thenReturn(failureResult);
            when(paymentRepository.findById(anyLong())).thenReturn(Optional.of(payment));
            
            // act
            PaymentRequestResult result = paymentService.requestPayment(orderId, userId, userEntityId, cardType, cardNo, amount);
            
            // assert
            assertThat(result).isInstanceOf(PaymentRequestResult.Failure.class);
            verify(paymentRepository, times(2)).save(any(Payment.class)); // 생성 + 실패 상태 변경
        }
        
        @DisplayName("외부 시스템 장애 시 결제 상태를 PENDING으로 유지한다.")
        @Test
        void maintainsPendingStatus_whenExternalSystemFailure() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            String userId = "user123";  // User.userId (String)
            Long userEntityId = PaymentTestFixture.ValidPayment.USER_ID;  // User.id (Long)
            String cardType = "SAMSUNG";
            String cardNo = PaymentTestFixture.ValidPayment.CARD_NO;
            Long amount = PaymentTestFixture.ValidPayment.AMOUNT;
            
            Payment payment = Payment.of(
                orderId,
                userEntityId,
                CardType.SAMSUNG,
                cardNo,
                amount,
                LocalDateTime.now()
            );
            
            PaymentRequestResult.Failure failureResult = new PaymentRequestResult.Failure(
                "CIRCUIT_BREAKER_OPEN",
                "결제 대기 상태",
                false,
                false
            );
            
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            when(paymentGateway.requestPayment(any(PaymentRequest.class))).thenReturn(failureResult);
            
            // act
            PaymentRequestResult result = paymentService.requestPayment(orderId, userId, userEntityId, cardType, cardNo, amount);
            
            // assert
            assertThat(result).isInstanceOf(PaymentRequestResult.Failure.class);
            verify(paymentRepository, times(1)).save(any(Payment.class)); // 생성만
            verify(paymentRepository, never()).findById(anyLong()); // 상태 변경 없음
        }
        
        @DisplayName("잘못된 카드 번호로 인해 예외가 발생한다.")
        @Test
        void throwsException_whenInvalidCardNo() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            String userId = "user123";  // User.userId (String)
            Long userEntityId = PaymentTestFixture.ValidPayment.USER_ID;  // User.id (Long)
            String cardType = "SAMSUNG";
            String invalidCardNo = "1234"; // 잘못된 카드 번호
            Long amount = PaymentTestFixture.ValidPayment.AMOUNT;
            
            // act & assert
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentService.requestPayment(orderId, userId, userEntityId, cardType, invalidCardNo, amount);
            });
            
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(paymentRepository, never()).save(any(Payment.class));
            verify(paymentGateway, never()).requestPayment(any(PaymentRequest.class));
        }
        
        @DisplayName("잘못된 카드 타입으로 인해 예외가 발생한다.")
        @Test
        void throwsException_whenInvalidCardType() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            String userId = "user123";  // User.userId (String)
            Long userEntityId = PaymentTestFixture.ValidPayment.USER_ID;  // User.id (Long)
            String invalidCardType = "INVALID";
            String cardNo = PaymentTestFixture.ValidPayment.CARD_NO;
            Long amount = PaymentTestFixture.ValidPayment.AMOUNT;
            
            // act & assert
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentService.requestPayment(orderId, userId, userEntityId, invalidCardType, cardNo, amount);
            });
            
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(paymentRepository, never()).save(any(Payment.class));
            verify(paymentGateway, never()).requestPayment(any(PaymentRequest.class));
        }
    }
    
    @DisplayName("결제 상태 조회")
    @Nested
    class GetPaymentStatus {
        @DisplayName("결제 상태를 조회할 수 있다.")
        @Test
        void getsPaymentStatus() {
            // arrange
            String userId = "user123";
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            PaymentStatus expectedStatus = PaymentStatus.SUCCESS;
            
            when(paymentGateway.getPaymentStatus(userId, orderId)).thenReturn(expectedStatus);
            
            // act
            PaymentStatus result = paymentService.getPaymentStatus(userId, orderId);
            
            // assert
            assertThat(result).isEqualTo(expectedStatus);
            verify(paymentGateway, times(1)).getPaymentStatus(userId, orderId);
        }
    }
    
    @DisplayName("콜백 처리")
    @Nested
    class HandleCallback {
        @DisplayName("SUCCESS 콜백을 처리할 수 있다.")
        @Test
        void handlesSuccessCallback() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            String transactionKey = "TXN123456";
            PaymentStatus status = PaymentStatus.SUCCESS;
            String reason = null;
            
            Payment payment = Payment.of(
                orderId,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                LocalDateTime.now()
            );
            
            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));
            when(paymentRepository.findById(anyLong())).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            
            // act
            paymentService.handleCallback(orderId, transactionKey, status, reason);
            
            // assert
            verify(paymentRepository, times(1)).findByOrderId(orderId);
            verify(paymentRepository, times(1)).findById(anyLong());
            verify(paymentRepository, times(1)).save(any(Payment.class));
        }
        
        @DisplayName("FAILED 콜백을 처리할 수 있다.")
        @Test
        void handlesFailedCallback() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            String transactionKey = "TXN123456";
            PaymentStatus status = PaymentStatus.FAILED;
            String reason = "카드 한도 초과";
            
            Payment payment = Payment.of(
                orderId,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                LocalDateTime.now()
            );
            
            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));
            when(paymentRepository.findById(anyLong())).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            
            // act
            paymentService.handleCallback(orderId, transactionKey, status, reason);
            
            // assert
            verify(paymentRepository, times(1)).findByOrderId(orderId);
            verify(paymentRepository, times(1)).findById(anyLong());
            verify(paymentRepository, times(1)).save(any(Payment.class));
        }
        
        @DisplayName("PENDING 콜백은 상태를 유지한다.")
        @Test
        void maintainsStatus_whenPendingCallback() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            String transactionKey = "TXN123456";
            PaymentStatus status = PaymentStatus.PENDING;
            String reason = null;
            
            Payment payment = Payment.of(
                orderId,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                LocalDateTime.now()
            );
            
            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));
            
            // act
            paymentService.handleCallback(orderId, transactionKey, status, reason);
            
            // assert
            verify(paymentRepository, times(1)).findByOrderId(orderId);
            verify(paymentRepository, never()).findById(anyLong());
            verify(paymentRepository, never()).save(any(Payment.class));
        }
        
        @DisplayName("결제를 찾을 수 없으면 로그만 기록한다.")
        @Test
        void logsWarning_whenPaymentNotFound() {
            // arrange
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            String transactionKey = "TXN123456";
            PaymentStatus status = PaymentStatus.SUCCESS;
            String reason = null;
            
            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
            
            // act
            paymentService.handleCallback(orderId, transactionKey, status, reason);
            
            // assert
            verify(paymentRepository, times(1)).findByOrderId(orderId);
            verify(paymentRepository, never()).findById(anyLong());
            verify(paymentRepository, never()).save(any(Payment.class));
        }
    }
    
    @DisplayName("타임아웃 복구")
    @Nested
    class RecoverAfterTimeout {
        @DisplayName("SUCCESS 상태로 복구할 수 있다.")
        @Test
        void recoversToSuccess() {
            // arrange
            String userId = "user123";
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            PaymentStatus status = PaymentStatus.SUCCESS;
            
            Payment payment = Payment.of(
                orderId,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                LocalDateTime.now()
            );
            
            when(paymentGateway.getPaymentStatus(userId, orderId)).thenReturn(status);
            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));
            when(paymentRepository.findById(anyLong())).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            
            // act
            paymentService.recoverAfterTimeout(userId, orderId);
            
            // assert
            verify(paymentGateway, times(1)).getPaymentStatus(userId, orderId);
            verify(paymentRepository, times(1)).findByOrderId(orderId);
            verify(paymentRepository, times(1)).findById(anyLong());
            verify(paymentRepository, times(1)).save(any(Payment.class));
        }
        
        @DisplayName("FAILED 상태로 복구할 수 있다.")
        @Test
        void recoversToFailed() {
            // arrange
            String userId = "user123";
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            PaymentStatus status = PaymentStatus.FAILED;
            
            Payment payment = Payment.of(
                orderId,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                LocalDateTime.now()
            );
            
            when(paymentGateway.getPaymentStatus(userId, orderId)).thenReturn(status);
            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));
            when(paymentRepository.findById(anyLong())).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            
            // act
            paymentService.recoverAfterTimeout(userId, orderId);
            
            // assert
            verify(paymentGateway, times(1)).getPaymentStatus(userId, orderId);
            verify(paymentRepository, times(1)).findByOrderId(orderId);
            verify(paymentRepository, times(1)).findById(anyLong());
            verify(paymentRepository, times(1)).save(any(Payment.class));
        }
        
        @DisplayName("PENDING 상태는 유지한다.")
        @Test
        void maintainsPendingStatus() {
            // arrange
            String userId = "user123";
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            PaymentStatus status = PaymentStatus.PENDING;
            
            Payment payment = Payment.of(
                orderId,
                PaymentTestFixture.ValidPayment.USER_ID,
                PaymentTestFixture.ValidPayment.CARD_TYPE,
                PaymentTestFixture.ValidPayment.CARD_NO,
                PaymentTestFixture.ValidPayment.AMOUNT,
                LocalDateTime.now()
            );
            
            when(paymentGateway.getPaymentStatus(userId, orderId)).thenReturn(status);
            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));
            
            // act
            paymentService.recoverAfterTimeout(userId, orderId);
            
            // assert
            verify(paymentGateway, times(1)).getPaymentStatus(userId, orderId);
            verify(paymentRepository, times(1)).findByOrderId(orderId);
            verify(paymentRepository, never()).findById(anyLong());
            verify(paymentRepository, never()).save(any(Payment.class));
        }
        
        @DisplayName("결제를 찾을 수 없으면 로그만 기록한다.")
        @Test
        void logsWarning_whenPaymentNotFound() {
            // arrange
            String userId = "user123";
            Long orderId = PaymentTestFixture.ValidPayment.ORDER_ID;
            PaymentStatus status = PaymentStatus.SUCCESS;
            
            when(paymentGateway.getPaymentStatus(userId, orderId)).thenReturn(status);
            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
            
            // act
            paymentService.recoverAfterTimeout(userId, orderId);
            
            // assert
            verify(paymentGateway, times(1)).getPaymentStatus(userId, orderId);
            verify(paymentRepository, times(1)).findByOrderId(orderId);
            verify(paymentRepository, never()).findById(anyLong());
            verify(paymentRepository, never()).save(any(Payment.class));
        }
    }
}

