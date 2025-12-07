package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 상품 도메인 서비스.
 * <p>
 * 상품 조회, 저장 등의 도메인 로직을 처리합니다.
 * Repository에 의존하며 비즈니스 규칙을 캡슐화합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@RequiredArgsConstructor
@Component
public class ProductService {
    private final ProductRepository productRepository;

    /**
     * 상품 ID로 상품을 조회합니다. (비관적 락)
     * <p>
     * 동시성 제어가 필요한 경우 사용합니다. (예: 재고 차감)
     * </p>
     *
     * @param productId 조회할 상품 ID
     * @return 조회된 상품
     * @throws CoreException 상품을 찾을 수 없는 경우
     */
    @Transactional
    public Product findByIdForUpdate(Long productId) {
        return productRepository.findByIdForUpdate(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId)));
    }

    /**
     * 상품 목록을 저장합니다.
     *
     * @param products 저장할 상품 목록
     */
    @Transactional
    public void saveAll(List<Product> products) {
        products.forEach(productRepository::save);
    }
}

