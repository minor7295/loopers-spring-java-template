package com.loopers.application.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 좋아요 애플리케이션 서비스.
 * <p>
 * 좋아요 조회, 저장, 삭제 등의 애플리케이션 로직을 처리합니다.
 * Repository에 의존하며 트랜잭션 관리를 담당합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@RequiredArgsConstructor
@Component
public class LikeService {
    private final LikeRepository likeRepository;

    /**
     * 사용자 ID와 상품 ID로 좋아요를 조회합니다.
     *
     * @param userId 사용자 ID
     * @param productId 상품 ID
     * @return 조회된 좋아요를 담은 Optional
     */
    @Transactional(readOnly = true)
    public Optional<Like> getLike(Long userId, Long productId) {
        return likeRepository.findByUserIdAndProductId(userId, productId);
    }

    /**
     * 좋아요를 저장합니다.
     *
     * @param like 저장할 좋아요
     * @return 저장된 좋아요
     */
    @Transactional
    public Like save(Like like) {
        return likeRepository.save(like);
    }

    /**
     * 좋아요를 삭제합니다.
     *
     * @param like 삭제할 좋아요
     */
    @Transactional
    public void delete(Like like) {
        likeRepository.delete(like);
    }

    /**
     * 사용자 ID로 좋아요한 상품 목록을 조회합니다.
     *
     * @param userId 사용자 ID
     * @return 좋아요 목록
     */
    @Transactional(readOnly = true)
    public List<Like> getLikesByUserId(Long userId) {
        return likeRepository.findAllByUserId(userId);
    }
}
