package com.loopers.infrastructure.paymentgateway;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PG 결제 게이트웨이 DTO.
 */
public class PaymentGatewayDto {

    /**
     * PG 결제 요청 DTO.
     */
    public record PaymentRequest(
        @JsonProperty("orderId") String orderId,
        @JsonProperty("cardType") CardType cardType,
        @JsonProperty("cardNo") String cardNo,
        @JsonProperty("amount") Long amount,
        @JsonProperty("callbackUrl") String callbackUrl
    ) {
    }

    /**
     * PG 결제 응답 DTO.
     */
    public record TransactionResponse(
        @JsonProperty("transactionKey") String transactionKey,
        @JsonProperty("status") TransactionStatus status,
        @JsonProperty("reason") String reason
    ) {
    }

    /**
     * PG 결제 상세 응답 DTO.
     */
    public record TransactionDetailResponse(
        @JsonProperty("transactionKey") String transactionKey,
        @JsonProperty("orderId") String orderId,
        @JsonProperty("cardType") CardType cardType,
        @JsonProperty("cardNo") String cardNo,
        @JsonProperty("amount") Long amount,
        @JsonProperty("status") TransactionStatus status,
        @JsonProperty("reason") String reason
    ) {
    }

    /**
     * PG 주문별 결제 목록 응답 DTO.
     */
    public record OrderResponse(
        @JsonProperty("orderId") String orderId,
        @JsonProperty("transactions") java.util.List<TransactionResponse> transactions
    ) {
    }

    /**
     * 카드 타입.
     */
    public enum CardType {
        SAMSUNG,
        KB,
        HYUNDAI
    }

    /**
     * 거래 상태.
     */
    public enum TransactionStatus {
        PENDING,
        SUCCESS,
        FAILED
    }

    /**
     * PG 콜백 요청 DTO (PG에서 보내는 TransactionInfo).
     */
    public record CallbackRequest(
        @JsonProperty("transactionKey") String transactionKey,
        @JsonProperty("orderId") String orderId,
        @JsonProperty("cardType") CardType cardType,
        @JsonProperty("cardNo") String cardNo,
        @JsonProperty("amount") Long amount,
        @JsonProperty("status") TransactionStatus status,
        @JsonProperty("reason") String reason
    ) {
    }

    /**
     * PG API 응답 래퍼.
     */
    public record ApiResponse<T>(
        @JsonProperty("meta") Metadata meta,
        @JsonProperty("data") T data
    ) {
        public record Metadata(
            @JsonProperty("result") Result result,
            @JsonProperty("errorCode") String errorCode,
            @JsonProperty("message") String message
        ) {
            public enum Result {
                SUCCESS,
                FAIL
            }
        }
    }
}

