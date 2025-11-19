package com.loopers.application.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * 좋아요 관리 파사드.
 * <p>
 * 좋아요 추가, 삭제, 목록 조회 유즈케이스를 처리하는 애플리케이션 서비스입니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final LikeRepository likeRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ExecutorService executorService;

    /**
     * 상품에 좋아요를 추가합니다.
     * <p>
     * 멱등성을 보장합니다. 이미 좋아요가 존재하는 경우 아무 작업도 수행하지 않습니다.
     * </p>
     * <p>
     * <b>동시성 제어 전략:</b>
     * <ul>
     *   <li><b>UNIQUE 제약조건 사용:</b> 데이터베이스 레벨에서 중복 삽입을 물리적으로 방지</li>
     *   <li><b>애플리케이션 레벨 한계:</b> 애플리케이션 레벨로는 race condition을 완전히 방지할 수 없음</li>
     *   <li><b>예외 처리:</b> UNIQUE 제약조건 위반 시 DataIntegrityViolationException 처리하여 멱등성 보장</li>
     * </ul>
     * </p>
     * <p>
     * <b>DBA 설득 근거 (유니크 인덱스 사용):</b>
     * <ul>
     *   <li><b>트래픽 패턴:</b> 좋아요는 고 QPS write-heavy 테이블이 아니며, 전체 서비스에서 차지하는 비중이 낮음</li>
     *   <li><b>애플리케이션 레벨 한계:</b> 동일 시점 동시 요청 시 select 시점엔 중복 없음 → insert 2번 발생 가능</li>
     *   <li><b>데이터 무결성:</b> DB만이 강한 무결성(Strong Consistency)을 제공할 수 있음</li>
     *   <li><b>비즈니스 데이터 보호:</b> 중복 좋아요로 인한 비즈니스 데이터 오염 방지</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID (String)
     * @param productId 상품 ID
     * @throws CoreException 사용자 또는 상품을 찾을 수 없는 경우
     */
    @Transactional
    public void addLike(String userId, Long productId) {
        User user = loadUser(userId);
        loadProduct(productId);

        // 먼저 일반 조회로 중복 체크 (대부분의 경우 빠르게 처리)
        // ⚠️ 주의: 애플리케이션 레벨 체크만으로는 race condition을 완전히 방지할 수 없음
        // 동시에 두 요청이 들어오면 둘 다 "없음"으로 판단 → 둘 다 저장 시도 가능
        Optional<Like> existingLike = likeRepository.findByUserIdAndProductId(user.getId(), productId);
        if (existingLike.isPresent()) {
            return;
        }

        // 저장 시도 (동시성 상황에서는 UNIQUE 제약조건 위반 예외 발생 가능)
        // ✅ UNIQUE 제약조건이 최종 보호: DB 레벨에서 중복 삽입을 물리적으로 방지
        Like like = Like.of(user.getId(), productId);
        try {
            likeRepository.save(like);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // UNIQUE 제약조건 위반 예외 처리
            // 동시에 여러 요청이 들어와서 모두 "없음"으로 판단하고 저장을 시도할 때,
            // 첫 번째만 성공하고 나머지는 UNIQUE 제약조건 위반 예외 발생
            // 이미 좋아요가 존재하는 경우이므로 정상 처리로 간주 (멱등성 보장)
            
            // 저장 실패 후 다시 한 번 확인 (다른 트랜잭션이 이미 저장했을 수 있음)
            Optional<Like> savedLike = likeRepository.findByUserIdAndProductId(user.getId(), productId);
            if (savedLike.isEmpty()) {
                // 예외가 발생했지만 실제로 저장되지 않은 경우 (드문 경우)
                // UNIQUE 제약조건 위반이지만 다른 이유일 수 있으므로 예외를 다시 던짐
                throw e;
            }
            // 이미 저장되어 있으므로 정상 처리로 간주
            return;
        }
    }

    /**
     * 상품의 좋아요를 취소합니다.
     * <p>
     * 멱등성을 보장합니다. 좋아요가 존재하지 않는 경우 아무 작업도 수행하지 않습니다.
     * </p>
     *
     * @param userId 사용자 ID (String)
     * @param productId 상품 ID
     * @throws CoreException 사용자 또는 상품을 찾을 수 없는 경우
     */
    @Transactional
    public void removeLike(String userId, Long productId) {
        User user = loadUser(userId);
        loadProduct(productId);

        Optional<Like> like = likeRepository.findByUserIdAndProductId(user.getId(), productId);
        if (like.isEmpty()) {
            return;
        }

        likeRepository.delete(like.get());
    }

    /**
     * 사용자가 좋아요한 상품 목록을 조회합니다.
     * <p>
     * 상품 정보 조회와 좋아요 수 집계를 병렬로 처리하여 성능을 최적화합니다.
     * </p>
     *
     * @param userId 사용자 ID (String)
     * @return 좋아요한 상품 목록
     * @throws CoreException 사용자를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public List<LikedProduct> getLikedProducts(String userId) {
        User user = loadUser(userId);

        // 사용자의 좋아요 목록 조회
        List<Like> likes = likeRepository.findAllByUserId(user.getId());

        if (likes.isEmpty()) {
            return List.of();
        }

        // 상품 ID 목록 추출
        List<Long> productIds = likes.stream()
            .map(Like::getProductId)
            .toList();

        // ✅ 병렬로 상품 정보 조회
        List<CompletableFuture<Product>> productFutures = productIds.stream()
            .map(productId -> CompletableFuture.supplyAsync(() -> {
                try {
                    return productRepository.findById(productId)
                        .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                            String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId)));
                } catch (CoreException e) {
                    throw new CompletionException(e);
                }
            }, executorService))
            .toList();

        // ✅ 병렬로 좋아요 수 집계
        CompletableFuture<Map<Long, Long>> likesCountFuture = CompletableFuture.supplyAsync(() ->
            likeRepository.countByProductIds(productIds),
            executorService);

        // 모든 작업 완료 대기
        CompletableFuture.allOf(
            productFutures.toArray(new CompletableFuture[0])
        ).join();

        // 결과 수집
        List<Product> products = productFutures.stream()
            .map(future -> {
                try {
                    return future.join();
                } catch (CompletionException e) {
                    if (e.getCause() instanceof CoreException) {
                        throw (CoreException) e.getCause();
                    }
                    String errorMessage = "상품 조회 중 오류가 발생했습니다.";
                    if (e.getCause() != null) {
                        errorMessage += " 원인: " + e.getCause().getMessage();
                    }
                    throw new CoreException(ErrorType.INTERNAL_ERROR, errorMessage);
                }
            })
            .toList();

        Map<Long, Long> likesCountMap = likesCountFuture.join();

        // 좋아요 목록을 상품 정보와 좋아요 수와 함께 변환
        return likes.stream()
            .map(like -> {
                Product product = products.stream()
                    .filter(p -> p.getId().equals(like.getProductId()))
                    .findFirst()
                    .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                        String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", like.getProductId())));
                Long likesCount = likesCountMap.getOrDefault(like.getProductId(), 0L);
                return LikedProduct.from(product, like, likesCount);
            })
            .toList();
    }

    private User loadUser(String userId) {
        User user = userRepository.findByUserId(userId);
        if (user == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return user;
    }

    private Product loadProduct(Long productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId)));
    }

    /**
     * 좋아요한 상품 정보.
     *
     * @param productId 상품 ID
     * @param name 상품 이름
     * @param price 상품 가격
     * @param stock 상품 재고
     * @param brandId 브랜드 ID
     * @param likesCount 좋아요 수
     */
    public record LikedProduct(
        Long productId,
        String name,
        Integer price,
        Integer stock,
        Long brandId,
        Long likesCount
    ) {
        /**
         * Product와 Like로부터 LikedProduct를 생성합니다.
         *
         * @param product 상품 엔티티
         * @param like 좋아요 엔티티
         * @param likesCount 좋아요 수
         * @return 생성된 LikedProduct
         */
        public static LikedProduct from(Product product, Like like, Long likesCount) {
            return new LikedProduct(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStock(),
                product.getBrandId(),
                likesCount
            );
        }
    }
}

