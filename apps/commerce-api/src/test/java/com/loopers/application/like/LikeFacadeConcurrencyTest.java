package com.loopers.application.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LikeFacade 동시성 테스트
 * <p>
 * 여러 스레드에서 동시에 좋아요 요청을 보내도 데이터 일관성이 유지되는지 검증합니다.
 * </p>
 */
@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@DisplayName("LikeFacade 동시성 테스트")
class LikeFacadeConcurrencyTest {

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private User createAndSaveUser(String userId, String email, long point) {
        User user = User.of(userId, email, "1990-01-01", Gender.MALE, Point.of(point));
        return userRepository.save(user);
    }

    private Brand createAndSaveBrand(String brandName) {
        Brand brand = Brand.of(brandName);
        return brandRepository.save(brand);
    }

    private Product createAndSaveProduct(String productName, int price, int stock, Long brandId) {
        Product product = Product.of(productName, price, stock, brandId);
        return productRepository.save(product);
    }

    @Test
    @DisplayName("동일한 상품에 대해 여러명이 좋아요를 요청해도, 상품의 좋아요 개수가 정상 반영되어야 한다")
    void concurrencyTest_likeShouldBeProperlyCounted() throws InterruptedException {
        // arrange
        Brand brand = createAndSaveBrand("테스트 브랜드");
        Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());
        Long productId = product.getId();

        int userCount = 10;
        List<User> users = new ArrayList<>();
        for (int i = 0; i < userCount; i++) {
            users.add(createAndSaveUser("user" + i, "user" + i + "@example.com", 0L));
        }

        ExecutorService executorService = Executors.newFixedThreadPool(userCount);
        CountDownLatch latch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // act
        for (User user : users) {
            executorService.submit(() -> {
                try {
                    likeFacade.addLike(user.getUserId(), productId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executorService.shutdown();

        // assert
        long actualLikesCount = likeRepository.countByProductIds(List.of(productId))
            .getOrDefault(productId, 0L);

        assertThat(actualLikesCount).isEqualTo(userCount);
        assertThat(successCount.get()).isEqualTo(userCount);
        assertThat(exceptions).isEmpty();
    }

    @Test
    @DisplayName("동일한 사용자가 동시에 여러번 좋아요를 요청해도, 정상적으로 카운트되어야 한다")
    void concurrencyTest_sameUserMultipleRequests_shouldBeCountedCorrectly() throws InterruptedException {
        // arrange
        Brand brand = createAndSaveBrand("테스트 브랜드");
        Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());
        Long productId = product.getId();
        User user = createAndSaveUser("testuser", "test@example.com", 0L);
        String userId = user.getUserId();

        int concurrentRequestCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(concurrentRequestCount);
        CountDownLatch latch = new CountDownLatch(concurrentRequestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // act
        for (int i = 0; i < concurrentRequestCount; i++) {
            executorService.submit(() -> {
                try {
                    likeFacade.addLike(userId, productId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executorService.shutdown();

        // assert
        // UNIQUE 제약조건으로 인해 정확히 1개의 좋아요만 저장되어야 함
        long actualLikesCount = likeRepository.countByProductIds(List.of(productId))
            .getOrDefault(productId, 0L);

        assertThat(actualLikesCount).isEqualTo(1L);
        // 애플리케이션 레벨 체크 또는 데이터베이스 UNIQUE 제약조건으로 인해
        // 모든 요청이 성공하거나 일부는 예외가 발생할 수 있지만,
        // 최종적으로는 1개의 좋아요만 저장되어야 함
        assertThat(successCount.get() + exceptions.size()).isEqualTo(concurrentRequestCount);
    }
}

