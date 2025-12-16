package com.loopers.application.metrics;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.metrics.ProductMetricsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ProductMetricsService 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ProductMetricsServiceTest {

    @Mock
    private ProductMetricsRepository productMetricsRepository;

    @InjectMocks
    private ProductMetricsService productMetricsService;

    @DisplayName("좋아요 수를 증가시킬 수 있다.")
    @Test
    void canIncrementLikeCount() {
        // arrange
        Long productId = 1L;
        ProductMetrics existingMetrics = new ProductMetrics(productId);
        existingMetrics.incrementLikeCount(); // 초기값: 1
        
        when(productMetricsRepository.findByProductIdForUpdate(productId))
            .thenReturn(Optional.of(existingMetrics));
        when(productMetricsRepository.save(any(ProductMetrics.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        productMetricsService.incrementLikeCount(productId);

        // assert
        assertThat(existingMetrics.getLikeCount()).isEqualTo(2L);
        verify(productMetricsRepository).findByProductIdForUpdate(productId);
        verify(productMetricsRepository).save(existingMetrics);
    }

    @DisplayName("좋아요 수를 감소시킬 수 있다.")
    @Test
    void canDecrementLikeCount() {
        // arrange
        Long productId = 1L;
        ProductMetrics existingMetrics = new ProductMetrics(productId);
        existingMetrics.incrementLikeCount(); // 초기값: 1
        
        when(productMetricsRepository.findByProductIdForUpdate(productId))
            .thenReturn(Optional.of(existingMetrics));
        when(productMetricsRepository.save(any(ProductMetrics.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        productMetricsService.decrementLikeCount(productId);

        // assert
        assertThat(existingMetrics.getLikeCount()).isEqualTo(0L);
        verify(productMetricsRepository).findByProductIdForUpdate(productId);
        verify(productMetricsRepository).save(existingMetrics);
    }

    @DisplayName("판매량을 증가시킬 수 있다.")
    @Test
    void canIncrementSalesCount() {
        // arrange
        Long productId = 1L;
        Integer quantity = 5;
        ProductMetrics existingMetrics = new ProductMetrics(productId);
        
        when(productMetricsRepository.findByProductIdForUpdate(productId))
            .thenReturn(Optional.of(existingMetrics));
        when(productMetricsRepository.save(any(ProductMetrics.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        productMetricsService.incrementSalesCount(productId, quantity);

        // assert
        assertThat(existingMetrics.getSalesCount()).isEqualTo(5L);
        verify(productMetricsRepository).findByProductIdForUpdate(productId);
        verify(productMetricsRepository).save(existingMetrics);
    }

    @DisplayName("조회 수를 증가시킬 수 있다.")
    @Test
    void canIncrementViewCount() {
        // arrange
        Long productId = 1L;
        ProductMetrics existingMetrics = new ProductMetrics(productId);
        
        when(productMetricsRepository.findByProductIdForUpdate(productId))
            .thenReturn(Optional.of(existingMetrics));
        when(productMetricsRepository.save(any(ProductMetrics.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        productMetricsService.incrementViewCount(productId);

        // assert
        assertThat(existingMetrics.getViewCount()).isEqualTo(1L);
        verify(productMetricsRepository).findByProductIdForUpdate(productId);
        verify(productMetricsRepository).save(existingMetrics);
    }

    @DisplayName("메트릭이 없으면 새로 생성한다.")
    @Test
    void createsNewMetrics_whenNotExists() {
        // arrange
        Long productId = 1L;
        
        when(productMetricsRepository.findByProductIdForUpdate(productId))
            .thenReturn(Optional.empty());
        when(productMetricsRepository.save(any(ProductMetrics.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        productMetricsService.incrementLikeCount(productId);

        // assert
        verify(productMetricsRepository).findByProductIdForUpdate(productId);
        // findOrCreate에서 1번, incrementLikeCount에서 1번 총 2번 호출됨
        verify(productMetricsRepository, times(2)).save(any(ProductMetrics.class));
    }

    @DisplayName("판매량 증가 시 null이나 0 이하의 수량은 무시된다.")
    @Test
    void ignoresInvalidQuantity_whenIncrementingSalesCount() {
        // arrange
        Long productId = 1L;
        ProductMetrics existingMetrics = new ProductMetrics(productId);
        Long initialSalesCount = existingMetrics.getSalesCount();
        Long initialVersion = existingMetrics.getVersion();
        
        when(productMetricsRepository.findByProductIdForUpdate(productId))
            .thenReturn(Optional.of(existingMetrics));
        when(productMetricsRepository.save(any(ProductMetrics.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // act
        productMetricsService.incrementSalesCount(productId, null);
        productMetricsService.incrementSalesCount(productId, 0);
        productMetricsService.incrementSalesCount(productId, -1);

        // assert
        // 유효하지 않은 수량은 무시되므로 값이 변경되지 않음
        assertThat(existingMetrics.getSalesCount()).isEqualTo(initialSalesCount);
        assertThat(existingMetrics.getVersion()).isEqualTo(initialVersion);
        // save()는 호출되지만 메트릭 값은 변경되지 않음
        verify(productMetricsRepository, times(3)).findByProductIdForUpdate(productId);
        verify(productMetricsRepository, times(3)).save(existingMetrics);
    }
}
