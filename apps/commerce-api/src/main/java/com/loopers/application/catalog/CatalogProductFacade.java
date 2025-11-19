package com.loopers.application.catalog;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductDetail;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 상품 조회 파사드.
 * <p>
 * 상품 목록 조회 및 상품 정보 조회 유즈케이스를 처리하는 애플리케이션 서비스입니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@RequiredArgsConstructor
@Component
public class CatalogProductFacade {
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;

    /**
     * 상품 목록을 조회합니다.
     *
     * @param brandId 브랜드 ID (선택)
     * @param sort 정렬 기준 (latest, price_asc, likes_desc)
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지당 상품 수
     * @return 상품 목록 조회 결과
     */
    public ProductInfoList getProducts(Long brandId, String sort, int page, int size) {
        long totalCount = productRepository.countAll(brandId);
        List<Product> products = productRepository.findAll(brandId, sort, page, size);
        List<ProductInfo> productsInfo = products.stream()
            .map(product -> getProduct(product.getId()))
            .toList();
        return new ProductInfoList(productsInfo, totalCount, page, size);
    }

    /**
     * 상품 정보를 조회합니다.
     *
     * @param productId 상품 ID
     * @return 상품 정보와 좋아요 수
     * @throws CoreException 상품을 찾을 수 없는 경우
     */
    public ProductInfo getProduct(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        
        // 브랜드 조회
        Brand brand = brandRepository.findById(product.getBrandId())
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        
        // ✅ Product.likeCount 필드 사용 (비동기 집계된 값)
        Long likesCount = product.getLikeCount();
        
        // ProductDetail 생성 (Aggregate 경계 준수: Brand 엔티티 대신 brandName만 전달)
        ProductDetail productDetail = ProductDetail.from(product, brand.getName(), likesCount);
        
        return new ProductInfo(productDetail);
    }

}

