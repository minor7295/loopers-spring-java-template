package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Product 엔티티를 위한 Spring Data JPA 리포지토리.
 * <p>
 * JpaRepository를 확장하여 기본 CRUD 기능을 제공합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface ProductJpaRepository extends JpaRepository<Product, Long> {
    /**
     * 브랜드 ID로 상품을 조회합니다.
     *
     * @param brandId 브랜드 ID
     * @param pageable 페이징 정보
     * @return 상품 페이지
     */
    Page<Product> findByBrandId(Long brandId, Pageable pageable);

    /**
     * 전체 상품을 조회합니다.
     *
     * @param pageable 페이징 정보
     * @return 상품 페이지
     */
    Page<Product> findAll(Pageable pageable);

    /**
     * 브랜드 ID로 상품 개수를 조회합니다.
     *
     * @param brandId 브랜드 ID
     * @return 상품 개수
     */
    long countByBrandId(Long brandId);

    /**
     * 상품 ID로 상품을 조회합니다. (비관적 락)
     * <p>
     * SELECT ... FOR UPDATE를 사용하여 동시성 제어를 보장합니다.
     * </p>
     *
     * @param productId 조회할 상품 ID
     * @return 조회된 상품을 담은 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :productId")
    Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
}

