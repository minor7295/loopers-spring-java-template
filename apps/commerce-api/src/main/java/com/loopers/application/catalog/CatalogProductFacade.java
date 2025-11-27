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
import java.util.Map;
import java.util.stream.Collectors;

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
    private final ProductCacheService productCacheService;

    /**
     * 상품 목록을 조회합니다.
     * <p>
     * Redis 캐시를 확인하고, 캐시에 없으면 DB에서 조회한 후 캐시에 저장합니다.
     * 배치 조회를 통해 N+1 쿼리 문제를 해결합니다.
     * </p>
     *
     * @param brandId 브랜드 ID (선택)
     * @param sort 정렬 기준 (latest, price_asc, likes_desc)
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지당 상품 수
     * @return 상품 목록 조회 결과
     */
    public ProductInfoList getProducts(Long brandId, String sort, int page, int size) {
        // sort 기본값 처리 (컨트롤러와 동일하게 "latest" 사용)
        String normalizedSort = (sort != null && !sort.isBlank()) ? sort : "latest";
        
        // 캐시에서 조회 시도
        ProductInfoList cachedResult = productCacheService.getCachedProductList(brandId, normalizedSort, page, size);
        if (cachedResult != null) {
            return cachedResult;
        }
        
        // 캐시에 없으면 DB에서 조회
        long totalCount = productRepository.countAll(brandId);
        List<Product> products = productRepository.findAll(brandId, normalizedSort, page, size);
        
        if (products.isEmpty()) {
            ProductInfoList emptyResult = new ProductInfoList(List.of(), totalCount, page, size);
            // 캐시 저장
            productCacheService.cacheProductList(brandId, normalizedSort, page, size, emptyResult);
            return emptyResult;
        }
        
        // ✅ 배치 조회로 N+1 쿼리 문제 해결
        // 브랜드 ID 수집
        List<Long> brandIds = products.stream()
            .map(Product::getBrandId)
            .distinct()
            .toList();
        
        // 브랜드 배치 조회 및 Map으로 변환 (O(1) 조회를 위해)
        Map<Long, Brand> brandMap = brandRepository.findAllById(brandIds).stream()
            .collect(Collectors.toMap(Brand::getId, brand -> brand));
        
        // 상품 정보 변환 (이미 조회한 Product 재사용)
        List<ProductInfo> productsInfo = products.stream()
            .map(product -> {
                Brand brand = brandMap.get(product.getBrandId());
                if (brand == null) {
                    throw new CoreException(ErrorType.NOT_FOUND,
                        String.format("브랜드를 찾을 수 없습니다. (브랜드 ID: %d)", product.getBrandId()));
                }
                // ✅ Product.likeCount 필드 사용 (비동기 집계된 값)
                ProductDetail productDetail = ProductDetail.from(product, brand.getName(), product.getLikeCount());
                return new ProductInfo(productDetail);
            })
            .toList();
        
        ProductInfoList result = new ProductInfoList(productsInfo, totalCount, page, size);
        
        // 캐시 저장
        productCacheService.cacheProductList(brandId, normalizedSort, page, size, result);
        
        return result;
    }

    /**
     * 상품 정보를 조회합니다.
     * <p>
     * Redis 캐시를 먼저 확인하고, 캐시에 없으면 DB에서 조회한 후 캐시에 저장합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @return 상품 정보와 좋아요 수
     * @throws CoreException 상품을 찾을 수 없는 경우
     */
    public ProductInfo getProduct(Long productId) {
        // 캐시에서 조회 시도
        ProductInfo cachedResult = productCacheService.getCachedProduct(productId);
        if (cachedResult != null) {
            return cachedResult;
        }
        
        // 캐시에 없으면 DB에서 조회
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        
        // 브랜드 조회
        Brand brand = brandRepository.findById(product.getBrandId())
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        
        // ✅ Product.likeCount 필드 사용 (비동기 집계된 값)
        Long likesCount = product.getLikeCount();
        
        // ProductDetail 생성 (Aggregate 경계 준수: Brand 엔티티 대신 brandName만 전달)
        ProductDetail productDetail = ProductDetail.from(product, brand.getName(), likesCount);
        
        ProductInfo result = new ProductInfo(productDetail);
        
        // 캐시에 저장
        productCacheService.cacheProduct(productId, result);
        
        return result;
    }

}

