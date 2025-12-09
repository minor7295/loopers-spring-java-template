package com.loopers.application.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 상품 애플리케이션 서비스.
 * <p>
 * 상품 조회, 저장 등의 애플리케이션 로직을 처리합니다.
 * Repository에 의존하며 트랜잭션 관리를 담당합니다.
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
     * 상품 ID로 상품을 조회합니다.
     *
     * @param productId 조회할 상품 ID
     * @return 조회된 상품
     * @throws CoreException 상품을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public Product getProduct(Long productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId)));
    }

    /**
     * 상품 ID 목록으로 상품 목록을 조회합니다.
     * <p>
     * 배치 조회를 통해 N+1 쿼리 문제를 해결합니다.
     * </p>
     *
     * @param productIds 조회할 상품 ID 목록
     * @return 조회된 상품 목록
     */
    @Transactional(readOnly = true)
    public List<Product> getProducts(List<Long> productIds) {
        return productRepository.findAllById(productIds);
    }

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
    public Product getProductForUpdate(Long productId) {
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

    /**
     * 상품 목록을 조회합니다.
     *
     * @param brandId 브랜드 ID (null이면 전체 조회)
     * @param sort 정렬 기준 (latest, price_asc, likes_desc)
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지당 상품 수
     * @return 상품 목록
     */
    @Transactional(readOnly = true)
    public List<Product> findAll(Long brandId, String sort, int page, int size) {
        return productRepository.findAll(brandId, sort, page, size);
    }

    /**
     * 상품 목록의 총 개수를 조회합니다.
     *
     * @param brandId 브랜드 ID (null이면 전체 조회)
     * @return 상품 총 개수
     */
    @Transactional(readOnly = true)
    public long countAll(Long brandId) {
        return productRepository.countAll(brandId);
    }
}

