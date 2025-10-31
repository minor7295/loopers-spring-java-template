package com.loopers.interfaces.api.point;

import com.loopers.domain.point.Point;

public class PointsV1Dto {
    public record PointsResponse(String userId, Long balance) {
        public static PointsResponse from(Point point) {
            return new PointsResponse(point.getUser().getUserId(), point.getBalance());
        }
    }
}


