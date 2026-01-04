package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * OrderRepository의 JPA 구현체.
 */
@RequiredArgsConstructor
@Repository
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;

    @Override
    public Order save(Order order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        return orderJpaRepository.findById(orderId);
    }

    @Override
    public List<Order> findAllByUserId(Long userId) {
        return orderJpaRepository.findAllByUserId(userId);
    }

    @Override
    public List<Order> findAllByStatus(OrderStatus status) {
        return orderJpaRepository.findAllByStatus(status);
    }
}


