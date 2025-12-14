package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 브랜드 조회 파사드.
 * <p>
 * 브랜드 정보 조회 유즈케이스를 처리하는 애플리케이션 서비스입니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@RequiredArgsConstructor
@Component
public class BrandService {
    private final BrandRepository brandRepository;

    /**
     * 브랜드 ID로 브랜드를 조회합니다.
     *
     * @param brandId 브랜드 ID
     * @return 조회된 브랜드
     * @throws CoreException 브랜드를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public Brand getBrand(Long brandId) {
        return brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
    }

    /**
     * 브랜드 ID 목록으로 브랜드 목록을 조회합니다.
     * <p>
     * 배치 조회를 통해 N+1 쿼리 문제를 해결합니다.
     * </p>
     *
     * @param brandIds 조회할 브랜드 ID 목록
     * @return 조회된 브랜드 목록
     */
    @Transactional(readOnly = true)
    public List<Brand> getBrands(List<Long> brandIds) {
        return brandRepository.findAllById(brandIds);
    }

    /**
     * 브랜드 정보를 조회합니다.
     *
     * @param brandId 브랜드 ID
     * @return 브랜드 정보
     * @throws CoreException 브랜드를 찾을 수 없는 경우
     */
    public BrandInfo getBrandInfo(Long brandId) {
        Brand brand = getBrand(brandId);
        return BrandInfo.from(brand);
    }

    /**
     * 브랜드 정보를 담는 레코드.
     *
     * @param id 브랜드 ID
     * @param name 브랜드 이름
     */
    public record BrandInfo(Long id, String name) {
        /**
         * Brand 엔티티로부터 BrandInfo를 생성합니다.
         *
         * @param brand 브랜드 엔티티
         * @return 생성된 BrandInfo
         */
        public static BrandInfo from(Brand brand) {
            return new BrandInfo(brand.getId(), brand.getName());
        }
    }
}

