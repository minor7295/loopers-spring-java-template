package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Order JPA Repository.
 */
public interface OrderJpaRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByUserId(Long userId);
    
    List<Order> findAllByStatus(OrderStatus status);
}


