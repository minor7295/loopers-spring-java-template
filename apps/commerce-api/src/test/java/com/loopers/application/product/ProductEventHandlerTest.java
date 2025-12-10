package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("ProductEventHandler 재고 차감 검증")
@RecordApplicationEvents
class ProductEventHandlerTest {

    @Autowired
    private com.loopers.interfaces.event.product.ProductEventListener productEventListener;

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

    // Helper methods for test fixtures
    private Brand createAndSaveBrand(String name) {
        Brand brand = Brand.of(name);
        return brandRepository.save(brand);
    }

    private Product createAndSaveProduct(String name, int price, int stock, Long brandId) {
        Product product = Product.of(name, price, stock, brandId);
        return productRepository.save(product);
    }

    @Test
    @DisplayName("동일한 상품에 대해 여러 주문이 동시에 요청되어도, 재고가 정상적으로 차감되어야 한다")
    void concurrencyTest_stockShouldBeProperlyDecreasedWhenOrdersCreated() throws InterruptedException {
        // arrange
        Brand brand = createAndSaveBrand("테스트 브랜드");
        Product product = createAndSaveProduct("테스트 상품", 10_000, 100, brand.getId());
        Long productId = product.getId();
        int initialStock = 100;

        int orderCount = 10;
        int quantityPerOrder = 5;

        ExecutorService executorService = Executors.newFixedThreadPool(orderCount);
        CountDownLatch latch = new CountDownLatch(orderCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // act
        for (int i = 0; i < orderCount; i++) {
            final int orderId = i + 1;
            executorService.submit(() -> {
                try {
                    OrderEvent.OrderCreated event = new OrderEvent.OrderCreated(
                        (long) orderId,
                        1L, // userId
                        null, // couponCode
                        10_000, // subtotal
                        0L, // usedPointAmount
                        List.of(new OrderEvent.OrderCreated.OrderItemInfo(productId, quantityPerOrder)),
                        LocalDateTime.now()
                    );
                    // ProductEventListener를 통해 호출하여 실제 운영 환경과 동일한 방식으로 테스트
                    // 재고 차감은 BEFORE_COMMIT으로 동기 처리되므로 예외가 발생하면 롤백됨
                    productEventListener.handleOrderCreated(event);
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
        // 재고 차감은 동기적으로 처리되므로 즉시 반영됨
        Product savedProduct = productRepository.findById(productId).orElseThrow();
        int expectedStock = initialStock - (successCount.get() * quantityPerOrder);

        assertThat(savedProduct.getStock()).isEqualTo(expectedStock);
        assertThat(successCount.get() + exceptions.size()).isEqualTo(orderCount);
    }
}

