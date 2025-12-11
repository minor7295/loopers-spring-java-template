package com.loopers.application.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * 재고 차감 명령.
 * <p>
 * 재고 차감을 위한 명령 객체입니다.
 * </p>
 *
 * @param productId 상품 ID
 * @param quantity 차감할 수량
 */
public record DeductStockCommand(
    Long productId,
    Integer quantity
) {
    public DeductStockCommand {
        if (productId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 ID는 필수입니다.");
        }
        if (quantity == null || quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감 수량은 1 이상이어야 합니다.");
        }
    }
}
