package com.loopers.application.purchasing;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponType;
import com.loopers.domain.coupon.UserCoupon;
import com.loopers.domain.coupon.UserCouponRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.payment.PaymentGatewayClient;
import com.loopers.infrastructure.payment.PaymentGatewayDto;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DisplayName("PurchasingFacade 주문 스펙 검증")
class PurchasingFacadeTest {

    @Autowired
    private PurchasingFacade purchasingFacade;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private ProductRepository productRepository;
    
    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;
    
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    
    @MockitoBean
    private PaymentGatewayClient paymentGatewayClient;

    @BeforeEach
    void setUp() {
        // 기본적으로 모든 테스트에서 결제 성공 응답을 반환하도록 설정
        // 개별 테스트에서 필요시 재설정 가능
        PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> successResponse =
            new PaymentGatewayDto.ApiResponse<>(
                new PaymentGatewayDto.ApiResponse.Metadata(
                    PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS,
                    null,
                    null
                ),
                new PaymentGatewayDto.TransactionResponse(
                    "TXN123456",
                    PaymentGatewayDto.TransactionStatus.SUCCESS,
                    null
                )
            );
        when(paymentGatewayClient.requestPayment(anyString(), any(PaymentGatewayDto.PaymentRequest.class)))
            .thenReturn(successResponse);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // Helper methods for test fixtures
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

    /**
     * 쿠폰을 생성하고 저장합니다.
     *
     * @param code 쿠폰 코드
     * @param type 쿠폰 타입
     * @param discountValue 할인 값
     * @return 저장된 쿠폰
     */
    private Coupon createAndSaveCoupon(String code, CouponType type, Integer discountValue) {
        Coupon coupon = Coupon.of(code, type, discountValue);
        return couponRepository.save(coupon);
    }

    /**
     * 사용자 쿠폰을 생성하고 저장합니다.
     * <p>
     * 쿠폰은 이미 저장된 상태여야 합니다.
     * </p>
     *
     * @param userId 사용자 ID
     * @param coupon 저장된 쿠폰
     * @return 저장된 사용자 쿠폰
     */
    private UserCoupon createAndSaveUserCoupon(Long userId, Coupon coupon) {
        UserCoupon userCoupon = UserCoupon.of(userId, coupon);
        return userCouponRepository.save(userCoupon);
    }

    @Test
    @DisplayName("주문 생성 시 재고 차감, 포인트 차감, 주문 완료, 외부 전송을 수행한다")
    void createOrder_successFlow() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드");
        Product product1 = createAndSaveProduct("상품1", 10_000, 10, brand.getId());
        Product product2 = createAndSaveProduct("상품2", 5_000, 5, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product1.getId(), 2),
            OrderItemCommand.of(product2.getId(), 1)
        );

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(user.getUserId(), commands, null, "SAMSUNG", "4111-1111-1111-1111");

        // assert
        // ✅ EDA 원칙: createOrder는 주문을 PENDING 상태로 생성하고 OrderEvent.OrderCreated 이벤트를 발행
        // ✅ ProductEventHandler가 OrderEvent.OrderCreated를 구독하여 재고 차감 처리
        // ✅ PaymentEventHandler가 PaymentEvent.PaymentRequested를 구독하여 Payment 생성 및 PG 결제 요청 처리
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        
        // ✅ 이벤트 핸들러가 재고 차감 처리 (통합 테스트이므로 실제 이벤트 핸들러가 실행됨)
        Product savedProduct1 = productRepository.findById(product1.getId()).orElseThrow();
        Product savedProduct2 = productRepository.findById(product2.getId()).orElseThrow();
        assertThat(savedProduct1.getStock()).isEqualTo(8); // 10 - 2
        assertThat(savedProduct2.getStock()).isEqualTo(4); // 5 - 1
        
        // 포인트 차감 확인 (usedPoint가 null이므로 포인트 차감 없음)
        User savedUser = userRepository.findByUserId(user.getUserId());
        assertThat(savedUser.getPoint().getValue()).isEqualTo(50_000L); // 포인트 차감 없음
    }

    @Test
    @DisplayName("주문 아이템이 비어 있으면 예외를 던진다")
    void createOrder_emptyItems_throwsException() {
        // arrange
        String userId = "user";
        List<OrderItemCommand> emptyCommands = List.of();

        // act & assert
        assertThatThrownBy(() -> purchasingFacade.createOrder(userId, emptyCommands, null, "SAMSUNG", "4111-1111-1111-1111"))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.BAD_REQUEST);
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 예외를 던진다")
    void createOrder_userNotFound() {
        // arrange
        String unknownUserId = "unknown";
        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(1L, 1)
        );

        // act & assert
        assertThatThrownBy(() -> purchasingFacade.createOrder(unknownUserId, commands, null, "SAMSUNG", "4111-1111-1111-1111"))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.NOT_FOUND);
    }

    @Test
    @DisplayName("상품 재고가 부족하면 예외를 던지고 포인트는 차감되지 않는다")
    void createOrder_stockNotEnough() {
        // arrange
        User user = createAndSaveUser("testuser2", "test2@example.com", 50_000L);
        final String userId = user.getUserId();

        Brand brand = createAndSaveBrand("브랜드2");
        Product product = createAndSaveProduct("상품", 10_000, 1, brand.getId());
        final Long productId = product.getId();
        final int initialStock = product.getStock();

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(productId, 2)
        );

        // act & assert
        // ✅ 재고 부족 사전 검증: PurchasingFacade에서 재고를 확인하여 예외 발생
        // ✅ 재고 차감은 ProductEventHandler에서 처리
        assertThatThrownBy(() -> purchasingFacade.createOrder(userId, commands, null, "SAMSUNG", "4111-1111-1111-1111"))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.BAD_REQUEST);

        // 롤백 확인: 포인트가 차감되지 않았는지 확인
        User savedUser = userRepository.findByUserId(userId);
        assertThat(savedUser.getPoint().getValue()).isEqualTo(50_000L);
        
        // 롤백 확인: 재고가 변경되지 않았는지 확인
        Product savedProduct = productRepository.findById(productId).orElseThrow();
        assertThat(savedProduct.getStock()).isEqualTo(initialStock);
    }

    @Test
    @DisplayName("상품 재고가 0이면 예외를 던지고 포인트는 차감되지 않는다")
    void createOrder_stockZero() {
        // arrange
        User user = createAndSaveUser("testuser2", "test2@example.com", 50_000L);
        final String userId = user.getUserId();

        Brand brand = createAndSaveBrand("브랜드2");
        Product product = createAndSaveProduct("상품", 10_000, 0, brand.getId());
        final Long productId = product.getId();
        final int initialStock = product.getStock();

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(productId, 1)
        );

        // act & assert
        assertThatThrownBy(() -> purchasingFacade.createOrder(userId, commands, null, "SAMSUNG", "4111-1111-1111-1111"))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.BAD_REQUEST);

        // 롤백 확인: 포인트가 차감되지 않았는지 확인
        User savedUser = userRepository.findByUserId(userId);
        assertThat(savedUser.getPoint().getValue()).isEqualTo(50_000L);
        
        // 롤백 확인: 재고가 변경되지 않았는지 확인
        Product savedProduct = productRepository.findById(productId).orElseThrow();
        assertThat(savedProduct.getStock()).isEqualTo(initialStock);
    }

    @Test
    @DisplayName("유저의 포인트 잔액이 부족하면 주문은 생성되지만 포인트 사용 실패 이벤트가 발행된다")
    void createOrder_pointNotEnough() {
        // arrange
        User user = createAndSaveUser("testuser2", "test2@example.com", 5_000L);
        final String userId = user.getUserId();

        Brand brand = createAndSaveBrand("브랜드2");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());
        final Long productId = product.getId();
        final int initialStock = product.getStock();

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(productId, 1)
        );

        // act
        // ✅ EDA 원칙: PurchasingFacade는 포인트 사전 검증을 하지 않음
        // ✅ 포인트 검증 및 차감은 PointEventHandler에서 처리
        // ✅ 포인트 부족 시 PointEventHandler에서 PointEvent.PointUsedFailed 이벤트 발행
        OrderInfo orderInfo = purchasingFacade.createOrder(userId, commands, 10_000L, "SAMSUNG", "4111-1111-1111-1111");

        // assert
        // 주문은 생성됨 (포인트 검증은 이벤트 핸들러에서 처리)
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(orderInfo.orderId()).isNotNull();
        
        // ✅ 재고는 차감됨 (ProductEventHandler가 동기적으로 처리)
        Product savedProduct = productRepository.findById(productId).orElseThrow();
        assertThat(savedProduct.getStock()).isEqualTo(initialStock - 1);
        
        // ✅ 포인트는 차감되지 않음 (포인트 부족으로 실패)
        // 주의: 포인트 사용 실패 이벤트 발행 검증은 PointEventHandlerTest에서 수행
        User savedUser = userRepository.findByUserId(userId);
        assertThat(savedUser.getPoint().getValue()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("동일 상품을 중복 주문하면 예외를 던진다")
    void createOrder_duplicateProducts_throwsException() {
        // arrange
        User user = createAndSaveUser("testuser3", "test3@example.com", 50_000L);
        final String userId = user.getUserId();

        Brand brand = createAndSaveBrand("브랜드3");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());
        final Long productId = product.getId();

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(productId, 1),
            OrderItemCommand.of(productId, 2)
        );

        // act & assert
        assertThatThrownBy(() -> purchasingFacade.createOrder(userId, commands, null, "SAMSUNG", "4111-1111-1111-1111"))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.BAD_REQUEST);

        // 주문이 저장되지 않았는지 확인
        List<OrderInfo> orders = purchasingFacade.getOrders(userId);
        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("사용자의 주문 목록을 조회한다")
    void getOrders_returnsUserOrders() {
        // arrange
        User user = createAndSaveUser("testuser4", "test4@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드4");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );
        purchasingFacade.createOrder(user.getUserId(), commands, null, "SAMSUNG", "4111-1111-1111-1111");

        // act
        List<OrderInfo> orders = purchasingFacade.getOrders(user.getUserId());

        // assert
        assertThat(orders).hasSize(1);
        // createOrder는 주문을 PENDING 상태로 생성하고, PG 결제 요청은 afterCommit 콜백에서 비동기로 실행됨
        assertThat(orders.get(0).status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("사용자의 단일 주문을 조회한다")
    void getOrder_returnsSingleOrder() {
        // arrange
        User user = createAndSaveUser("testuser5", "test5@example.com", 50_000L);
        Brand brand = createAndSaveBrand("브랜드5");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );
        OrderInfo createdOrder = purchasingFacade.createOrder(user.getUserId(), commands, null, "SAMSUNG", "4111-1111-1111-1111");

        // act
        OrderInfo found = purchasingFacade.getOrder(user.getUserId(), createdOrder.orderId());

        // assert
        assertThat(found.orderId()).isEqualTo(createdOrder.orderId());
        // createOrder는 주문을 PENDING 상태로 생성하고, PG 결제 요청은 afterCommit 콜백에서 비동기로 실행됨
        assertThat(found.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("다른 사용자의 주문은 조회할 수 없다")
    void getOrder_withDifferentUser_throwsException() {
        // arrange
        User user1 = createAndSaveUser("user1", "user1@example.com", 50_000L);
        User user2 = createAndSaveUser("user2", "user2@example.com", 50_000L);
        final String user1Id = user1.getUserId();
        final String user2Id = user2.getUserId();

        Brand brand = createAndSaveBrand("브랜드6");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product.getId(), 1)
        );
        OrderInfo user1Order = purchasingFacade.createOrder(user1Id, commands, null, "SAMSUNG", "4111-1111-1111-1111");
        final Long orderId = user1Order.orderId();

        // act & assert
        assertThatThrownBy(() -> purchasingFacade.getOrder(user2Id, orderId))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.NOT_FOUND);
    }

    @Test
    @DisplayName("주문 전체 흐름에 대해 원자성이 보장되어야 한다 - 실패 시 모든 작업이 롤백된다")
    void createOrder_atomicityGuaranteed() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        final String userId = user.getUserId();
        final long initialPoint = user.getPoint().getValue();

        Brand brand = createAndSaveBrand("브랜드");
        Product product1 = createAndSaveProduct("상품1", 10_000, 5, brand.getId());
        Product product2 = createAndSaveProduct("상품2", 20_000, 3, brand.getId());
        final Long product1Id = product1.getId();
        final Long product2Id = product2.getId();
        final int initialStock1 = product1.getStock();
        final int initialStock2 = product2.getStock();

        // product2의 재고가 부족한 상황 (3개 재고인데 5개 주문)
        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product1Id, 2),
            OrderItemCommand.of(product2Id, 5) // 재고 부족
        );

        // act & assert
        assertThatThrownBy(() -> purchasingFacade.createOrder(userId, commands, null, "SAMSUNG", "4111-1111-1111-1111"))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.BAD_REQUEST);

        // 롤백 확인: 포인트가 차감되지 않았는지 확인
        User savedUser = userRepository.findByUserId(userId);
        assertThat(savedUser.getPoint().getValue()).isEqualTo(initialPoint);
        
        // 롤백 확인: 모든 상품의 재고가 변경되지 않았는지 확인
        Product savedProduct1 = productRepository.findById(product1Id).orElseThrow();
        Product savedProduct2 = productRepository.findById(product2Id).orElseThrow();
        assertThat(savedProduct1.getStock()).isEqualTo(initialStock1);
        assertThat(savedProduct2.getStock()).isEqualTo(initialStock2);
        
        // 롤백 확인: 주문이 저장되지 않았는지 확인
        List<OrderInfo> orders = purchasingFacade.getOrders(userId);
        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("주문 성공 시, 모든 처리는 정상 반영되어야 한다 - 재고, 포인트, 주문 모두 반영")
    void createOrder_success_allOperationsReflected() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 100_000L);
        final String userId = user.getUserId();
        final long initialPoint = user.getPoint().getValue();

        Brand brand = createAndSaveBrand("브랜드");
        Product product1 = createAndSaveProduct("상품1", 10_000, 10, brand.getId());
        Product product2 = createAndSaveProduct("상품2", 15_000, 5, brand.getId());
        final Long product1Id = product1.getId();
        final Long product2Id = product2.getId();
        final int initialStock1 = product1.getStock();
        final int initialStock2 = product2.getStock();

        List<OrderItemCommand> commands = List.of(
            OrderItemCommand.of(product1Id, 3),
            OrderItemCommand.of(product2Id, 2)
        );

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(userId, commands, null, "SAMSUNG", "4111-1111-1111-1111");

        // assert
        // ✅ EDA 원칙: createOrder는 주문을 PENDING 상태로 생성하고 OrderEvent.OrderCreated 이벤트를 발행
        // ✅ ProductEventHandler가 OrderEvent.OrderCreated를 구독하여 재고 차감 처리
        // ✅ PaymentEventHandler가 PaymentEvent.PaymentRequested를 구독하여 Payment 생성 및 PG 결제 요청 처리
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(orderInfo.items()).hasSize(2);
        
        // ✅ 이벤트 핸들러가 재고 차감 처리 (통합 테스트이므로 실제 이벤트 핸들러가 실행됨)
        Product savedProduct1 = productRepository.findById(product1Id).orElseThrow();
        Product savedProduct2 = productRepository.findById(product2Id).orElseThrow();
        assertThat(savedProduct1.getStock()).isEqualTo(initialStock1 - 3);
        assertThat(savedProduct2.getStock()).isEqualTo(initialStock2 - 2);
        
        // 포인트 차감 확인 (usedPoint가 null이므로 포인트 차감 없음)
        User savedUser = userRepository.findByUserId(userId);
        assertThat(savedUser.getPoint().getValue()).isEqualTo(initialPoint); // 포인트 차감 없음
        
        // 주문이 저장되었는지 확인
        List<OrderInfo> orders = purchasingFacade.getOrders(userId);
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).orderId()).isEqualTo(orderInfo.orderId());
    }

    @Test
    @DisplayName("정액 쿠폰을 적용하여 주문할 수 있다")
    void createOrder_withFixedAmountCoupon_success() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        String userId = user.getUserId();
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        Coupon coupon = createAndSaveCoupon("FIXED5000", CouponType.FIXED_AMOUNT, 5_000);
        createAndSaveUserCoupon(user.getId(), coupon);

        List<OrderItemCommand> commands = List.of(
            new OrderItemCommand(product.getId(), 1, "FIXED5000")
        );

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(userId, commands, null, "SAMSUNG", "4111-1111-1111-1111");

        // assert
        // ✅ EDA 원칙: PurchasingFacade는 주문을 생성하고 이벤트를 발행하는 책임만 가짐
        // ✅ 쿠폰 할인 적용은 CouponEventHandler와 OrderEventHandler의 책임
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(orderInfo.orderId()).isNotNull();
        // 주의: 쿠폰 할인 적용 및 쿠폰 사용 여부 검증은 CouponEventHandler/OrderEventHandler 테스트에서 수행
    }

    @Test
    @DisplayName("정률 쿠폰을 적용하여 주문할 수 있다")
    void createOrder_withPercentageCoupon_success() {
        // arrange
        User user = createAndSaveUser("testuser", "test@example.com", 50_000L);
        String userId = user.getUserId();
        Brand brand = createAndSaveBrand("브랜드");
        Product product = createAndSaveProduct("상품", 10_000, 10, brand.getId());

        Coupon coupon = createAndSaveCoupon("PERCENT20", CouponType.PERCENTAGE, 20);
        createAndSaveUserCoupon(user.getId(), coupon);

        List<OrderItemCommand> commands = List.of(
            new OrderItemCommand(product.getId(), 1, "PERCENT20")
        );

        // act
        OrderInfo orderInfo = purchasingFacade.createOrder(userId, commands, null, "SAMSUNG", "4111-1111-1111-1111");

        // assert
        // ✅ EDA 원칙: PurchasingFacade는 주문을 생성하고 이벤트를 발행하는 책임만 가짐
        // ✅ 쿠폰 할인 적용은 CouponEventHandler와 OrderEventHandler의 책임
        assertThat(orderInfo.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(orderInfo.orderId()).isNotNull();
        // 주의: 쿠폰 할인 적용 및 쿠폰 사용 여부 검증은 CouponEventHandler/OrderEventHandler 테스트에서 수행
    }

    // 주의: 쿠폰 검증 테스트는 CouponEventHandler 테스트로 이동해야 함
    // 쿠폰 검증(존재 여부, 소유 여부, 사용 가능 여부)은 CouponEventHandler에서 비동기로 처리되므로,
    // PurchasingFacade에서는 검증할 수 없음 (이벤트 핸들러의 책임)

}
