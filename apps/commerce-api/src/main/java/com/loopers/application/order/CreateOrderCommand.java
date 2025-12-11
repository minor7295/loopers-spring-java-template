package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.List;

/**
 * 주문 생성 명령.
 * <p>
 * 주문 생성을 위한 명령 객체입니다.
 * </p>
 *
 * @param userId 사용자 ID
 * @param items 주문 아이템 목록
 * @param couponCode 쿠폰 코드 (선택)
 * @param subtotal 주문 소계 (쿠폰 할인 전 금액)
 * @param usedPointAmount 사용할 포인트 금액
 */
public record CreateOrderCommand(
    Long userId,
    List<OrderItem> items,
    String couponCode,
    Integer subtotal,
    Long usedPointAmount
) {
    public CreateOrderCommand {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자 ID는 필수입니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 아이템은 1개 이상이어야 합니다.");
        }
        if (subtotal == null || subtotal < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 소계는 0 이상이어야 합니다.");
        }
        if (usedPointAmount == null || usedPointAmount < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용 포인트 금액은 0 이상이어야 합니다.");
        }
    }
}
