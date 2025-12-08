package com.loopers.domain.payment;

/**
 * 결제 상태.
 */
public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED;

    /**
     * 결제가 완료되었는지 확인합니다.
     *
     * @return 완료 여부 (SUCCESS 또는 FAILED)
     */
    public boolean isCompleted() {
        return this == SUCCESS || this == FAILED;
    }

    /**
     * 결제가 성공했는지 확인합니다.
     *
     * @return 성공 여부
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}

