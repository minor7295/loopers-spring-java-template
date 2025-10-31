package com.loopers.domain.point;

import com.loopers.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PointService {
    private final PointRepository pointRepository;

    public Point create(User user, Long balance) {
        Point point = Point.of(user, balance);
        return pointRepository.save(point);
    }

    public Point findByUserId(String userId) {
        return pointRepository.findByUserId(userId);
    }
}


