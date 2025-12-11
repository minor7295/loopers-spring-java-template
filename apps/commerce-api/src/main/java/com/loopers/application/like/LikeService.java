package com.loopers.application.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeEvent;
import com.loopers.domain.like.LikeEventPublisher;
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
    private final LikeEventPublisher likeEventPublisher;

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
     * <p>
     * 저장 성공 시 좋아요 추가 이벤트를 발행합니다.
     * </p>
     *
     * @param like 저장할 좋아요
     * @return 저장된 좋아요
     */
    @Transactional
    public Like save(Like like) {
        Like savedLike = likeRepository.save(like);
        
        // ✅ 도메인 이벤트 발행: 좋아요가 추가되었음 (과거 사실)
        likeEventPublisher.publish(LikeEvent.LikeAdded.from(savedLike));
        
        return savedLike;
    }

    /**
     * 좋아요를 삭제합니다.
     * <p>
     * 삭제 전에 좋아요 취소 이벤트를 발행합니다.
     * </p>
     *
     * @param like 삭제할 좋아요
     */
    @Transactional
    public void delete(Like like) {
        // ✅ 도메인 이벤트 발행: 좋아요가 취소되었음 (과거 사실)
        likeEventPublisher.publish(LikeEvent.LikeRemoved.from(like));
        
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
