package com.loopers.domain.product;

import com.loopers.application.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ProductService 테스트.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService")
public class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @DisplayName("상품 조회 (비관적 락)")
    @Nested
    class FindProductForUpdate {
        @DisplayName("상품 ID로 상품을 조회할 수 있다. (비관적 락)")
        @Test
        void findsProductByIdForUpdate() {
            // arrange
            Long productId = 1L;
            Product expectedProduct = Product.of("상품", 10_000, 10, 1L);
            when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(expectedProduct));

            // act
            Product result = productService.findByIdForUpdate(productId);

            // assert
            assertThat(result).isEqualTo(expectedProduct);
            verify(productRepository, times(1)).findByIdForUpdate(productId);
        }

        @DisplayName("상품을 찾을 수 없으면 예외가 발생한다.")
        @Test
        void throwsException_whenProductNotFound() {
            // arrange
            Long productId = 999L;
            when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productService.findByIdForUpdate(productId);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(result.getMessage()).contains("상품을 찾을 수 없습니다");
            verify(productRepository, times(1)).findByIdForUpdate(productId);
        }
    }

    @DisplayName("상품 저장")
    @Nested
    class SaveProducts {
        @DisplayName("상품 목록을 저장할 수 있다.")
        @Test
        void savesAllProducts() {
            // arrange
            Product product1 = Product.of("상품1", 10_000, 10, 1L);
            Product product2 = Product.of("상품2", 20_000, 5, 1L);
            List<Product> products = List.of(product1, product2);
            when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // act
            productService.saveAll(products);

            // assert
            verify(productRepository, times(1)).save(product1);
            verify(productRepository, times(1)).save(product2);
        }
    }
}

