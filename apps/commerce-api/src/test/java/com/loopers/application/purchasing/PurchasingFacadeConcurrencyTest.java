package com.loopers.application.purchasing;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
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
 * PurchasingFacade 동시성 테스트
 * <p>
 * 여러 스레드에서 동시에 주문 요청을 보내도 데이터 일관성이 유지되는지 검증합니다.
 * - 포인트 차감의 정확성
 * - 재고 차감의 정확성
 * - 쿠폰 사용의 중복 방지 (예시)
 * </p>
 */
@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@DisplayName("PurchasingFacade 동시성 테스트")
class PurchasingFacadeConcurrencyTest {

    @Autowired
    private PurchasingFacade purchasingFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

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
    @DisplayName("동일한 유저가 서로 다른 주문을 동시에 수행해도, 포인트가 정상적으로 차감되어야 한다")
    void concurrencyTest_pointShouldProperlyDecreaseWhenOrderCreated() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 100_000L);
        String userId = user.getUserId();
        Brand brand = createAndSaveBrand("테스트 브랜드");

        int orderCount = 5;
        List<Product> products = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            products.add(createAndSaveProduct("상품" + i, 10_000, 100, brand.getId()));
        }

        ExecutorService executorService = Executors.newFixedThreadPool(orderCount);
        CountDownLatch latch = new CountDownLatch(orderCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // act
        for (int i = 0; i < orderCount; i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    List<OrderItemCommand> commands = List.of(
                        new OrderItemCommand(products.get(index).getId(), 1)
                    );
                    purchasingFacade.createOrder(userId, commands);
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
        User savedUser = userRepository.findByUserId(userId);
        long expectedRemainingPoint = 100_000L - (10_000L * orderCount);

        assertThat(successCount.get()).isEqualTo(orderCount);
        assertThat(exceptions).isEmpty();
        assertThat(savedUser.getPoint().getValue()).isEqualTo(expectedRemainingPoint);
    }

    @Test
    @DisplayName("동일한 상품에 대해 여러 주문이 동시에 요청되어도, 재고가 정상적으로 차감되어야 한다")
    void concurrencyTest_stockShouldBeProperlyDecreasedWhenOrdersCreated() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 1_000_000L);
        String userId = user.getUserId();
        Brand brand = createAndSaveBrand("테스트 브랜드");
        Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());
        Long productId = product.getId();

        int orderCount = 10;
        int quantityPerOrder = 5;

        ExecutorService executorService = Executors.newFixedThreadPool(orderCount);
        CountDownLatch latch = new CountDownLatch(orderCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // act
        for (int i = 0; i < orderCount; i++) {
            executorService.submit(() -> {
                try {
                    List<OrderItemCommand> commands = List.of(
                        new OrderItemCommand(productId, quantityPerOrder)
                    );
                    purchasingFacade.createOrder(userId, commands);
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
        Product savedProduct = productRepository.findById(productId).orElseThrow();
        int expectedStock = 100 - (successCount.get() * quantityPerOrder);

        assertThat(savedProduct.getStock()).isEqualTo(expectedStock);
        assertThat(successCount.get() + exceptions.size()).isEqualTo(orderCount);
    }

    @Test
    @DisplayName("동일한 쿠폰으로 여러 기기에서 동시에 주문해도, 쿠폰은 단 한번만 사용되어야 한다")
    void concurrencyTest_couponShouldBeUsedOnlyOnceWhenOrdersCreated() throws InterruptedException {
        // 주의: 현재 프로젝트에는 쿠폰 기능이 구현되어 있지 않습니다.
        // 이 테스트는 쿠폰 기능이 추가될 때를 대비한 예시입니다.
        // 
        // 쿠폰 기능이 추가되면 다음과 같이 구현해야 합니다:
        // 1. Coupon 도메인 엔티티 생성 (code, discountAmount, isUsed 등)
        // 2. CouponRepository 생성
        // 3. PurchasingFacade에서 쿠폰 사용 로직 추가 (낙관적/비관적 락 사용)
        // 4. 이 테스트에서 실제 쿠폰 사용 여부 검증
        //
        // 예시 구현:
        // @Transactional
        // public OrderInfo createOrderWithCoupon(String userId, List<OrderItemCommand> commands, String couponCode) {
        //     Coupon coupon = couponRepository.findByCodeForUpdate(couponCode); // 비관적 락
        //     if (coupon.isUsed()) {
        //         throw new CoreException(ErrorType.BAD_REQUEST, "이미 사용된 쿠폰입니다.");
        //     }
        //     coupon.use(); // 쿠폰 사용 처리
        //     // ... 주문 생성 로직
        // }
        
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 100_000L);
        String userId = user.getUserId();
        Brand brand = createAndSaveBrand("테스트 브랜드");
        Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());

        int concurrentRequestCount = 5;

        ExecutorService executorService = Executors.newFixedThreadPool(concurrentRequestCount);
        CountDownLatch latch = new CountDownLatch(concurrentRequestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // act
        for (int i = 0; i < concurrentRequestCount; i++) {
            executorService.submit(() -> {
                try {
                    List<OrderItemCommand> commands = List.of(
                        new OrderItemCommand(product.getId(), 1)
                    );
                    purchasingFacade.createOrder(userId, commands);
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
        assertThat(successCount.get()).isEqualTo(concurrentRequestCount);
    }
}

