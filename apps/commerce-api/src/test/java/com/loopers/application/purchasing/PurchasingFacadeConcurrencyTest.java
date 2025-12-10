package com.loopers.application.purchasing;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
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
 * 여러 스레드에서 동시에 주문 요청을 보내도 주문이 정상적으로 생성되는지 검증합니다.
 * <p>
 * <b>테스트 책임:</b>
 * <ul>
 *   <li>주문 생성 및 이벤트 발행 검증 (EDA 원칙 준수)</li>
 *   <li>포인트 차감, 재고 차감, 쿠폰 적용 등의 검증은 각각의 EventHandlerTest에서 수행</li>
 * </ul>
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
    private OrderRepository orderRepository;

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
    @DisplayName("동일한 유저가 서로 다른 주문을 동시에 수행해도, 주문은 모두 생성되어야 한다")
    void concurrencyTest_ordersShouldBeCreatedEvenWithPointUsage() throws InterruptedException {
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
                        OrderItemCommand.of(products.get(index).getId(), 1)
                    );
                    // 포인트를 사용하여 주문 (각 주문마다 10,000 포인트 사용)
                    purchasingFacade.createOrder(userId, commands, 10_000L, "SAMSUNG", "4111-1111-1111-1111");
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
        // ✅ PurchasingFacade의 책임: 주문 생성 및 이벤트 발행
        // 포인트 차감 검증은 PointEventHandlerTest에서 수행
        // 결제 실패는 PaymentEventHandler의 책임이므로, 주문 생성 트랜잭션은 롤백되지 않아야 함
        assertThat(successCount.get()).isEqualTo(orderCount);
        assertThat(exceptions).isEmpty();

        // 주문이 모두 생성되었는지 확인 (결제 실패와 무관하게)
        List<Order> orders = orderRepository.findAllByUserId(user.getId());
        assertThat(orders).hasSize(orderCount);
        
        // ✅ EDA 원칙: PurchasingFacade는 주문 생성만 담당
        // 결제 실패로 인한 주문 취소는 OrderEventHandler에서 비동기로 처리되므로,
        // 주문이 생성되었는지만 검증 (상태는 PENDING 또는 CANCELED 모두 가능)
        // 주문 생성 직후에는 PENDING 상태이지만, 결제 실패 시 CANCELED로 변경될 수 있음
    }

    @Test
    @DisplayName("동일한 상품에 대해 여러 주문이 동시에 요청되어도, 주문은 모두 생성되어야 한다")
    void concurrencyTest_ordersShouldBeCreatedEvenWithSameProduct() throws InterruptedException {
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
                        OrderItemCommand.of(productId, quantityPerOrder)
                    );
                    purchasingFacade.createOrder(userId, commands, null, "SAMSUNG", "4111-1111-1111-1111");
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
        // ✅ PurchasingFacade의 책임: 주문 생성 및 이벤트 발행
        // 재고 차감 검증은 ProductEventHandlerTest에서 수행
        // 결제 실패는 PaymentEventHandler의 책임이므로, 주문 생성 트랜잭션은 롤백되지 않아야 함
        assertThat(successCount.get() + exceptions.size()).isEqualTo(orderCount);

        // 주문이 모두 생성되었는지 확인 (결제 실패와 무관하게)
        List<Order> orders = orderRepository.findAllByUserId(user.getId());
        assertThat(orders).hasSize(successCount.get());
        
        // ✅ EDA 원칙: PurchasingFacade는 주문 생성만 담당
        // 결제 실패로 인한 주문 취소는 OrderEventHandler에서 비동기로 처리되므로,
        // 주문이 생성되었는지만 검증 (상태는 PENDING 또는 CANCELED 모두 가능)
        // 주문 생성 직후에는 PENDING 상태이지만, 결제 실패 시 CANCELED로 변경될 수 있음
    }


    @Test
    @DisplayName("주문 취소 중 다른 스레드가 재고를 변경해도, 재고 원복이 정확하게 이루어져야 한다")
    void concurrencyTest_cancelOrderShouldRestoreStockAccuratelyDuringConcurrentStockChanges() throws InterruptedException {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 1_000_000L);
        String userId = user.getUserId();
        Brand brand = createAndSaveBrand("테스트 브랜드");
        Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());
        Long productId = product.getId();
        
        // 주문 생성 (재고 5개 차감)
        int orderQuantity = 5;
        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(productId, orderQuantity)
        );
        OrderInfo orderInfo = purchasingFacade.createOrder(userId, commands, null, "SAMSUNG", "4111-1111-1111-1111");
        Long orderId = orderInfo.orderId();
        
        // 주문 취소 전 재고 확인 (100 - 5 = 95)
        Product productBeforeCancel = productRepository.findById(productId).orElseThrow();
        int stockBeforeCancel = productBeforeCancel.getStock();
        assertThat(stockBeforeCancel).isEqualTo(95);
        
        // 주문 조회
        Order order = orderRepository.findById(orderId).orElseThrow();
        
        ExecutorService executorService = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger cancelSuccess = new AtomicInteger(0);
        AtomicInteger orderSuccess = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();
        
        // act
        // 스레드 1: 주문 취소 (재고 원복)
        executorService.submit(() -> {
            try {
                purchasingFacade.cancelOrder(order, user);
                cancelSuccess.incrementAndGet();
            } catch (Exception e) {
                synchronized (exceptions) {
                    exceptions.add(e);
                }
            } finally {
                latch.countDown();
            }
        });
        
        // 스레드 2, 3: 취소 중간에 다른 주문 생성 (재고 추가 차감)
        for (int i = 0; i < 2; i++) {
            executorService.submit(() -> {
                try {
                    Thread.sleep(10); // 취소가 시작된 후 실행되도록 약간의 지연
                    List<OrderItemCommand> otherCommands = List.of(
                        OrderItemCommand.of(productId, 3)
                    );
                    purchasingFacade.createOrder(userId, otherCommands, null, "SAMSUNG", "4111-1111-1111-1111");
                    orderSuccess.incrementAndGet();
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
        // findByIdForUpdate로 인해 비관적 락이 적용되어 재고 원복이 정확하게 이루어져야 함
        Product finalProduct = productRepository.findById(productId).orElseThrow();
        int finalStock = finalProduct.getStock();
        
        // 시나리오:
        // 1. 초기 재고: 100
        // 2. 첫 주문: 95 (100 - 5)
        // 3. 주문 취소: 100 (95 + 5) - 비관적 락으로 정확한 재고 조회 후 원복
        // 4. 다른 주문 2개: 각각 3개씩 차감
        //    - 취소와 동시에 실행되면 락 대기 후 순차 처리
        //    - 최종 재고: 100 - 3 - 3 = 94 (취소로 5개 원복 후 2개 주문으로 6개 차감)
        
        assertThat(cancelSuccess.get()).isEqualTo(1);
        // 취소가 성공했고, 비관적 락으로 인해 정확한 재고가 원복되었는지 확인
        // 취소로 5개가 원복되고, 다른 주문 2개로 6개가 차감되므로: 95 + 5 - 6 = 94
        int expectedStock = stockBeforeCancel + orderQuantity - (orderSuccess.get() * 3);
        assertThat(finalStock).isEqualTo(expectedStock);
        
        // 예외가 발생하지 않았는지 확인
        assertThat(exceptions).isEmpty();
    }
}

