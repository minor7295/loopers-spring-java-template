package com.loopers.application.purchasing;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.UserCoupon;
import com.loopers.domain.coupon.UserCouponRepository;
import com.loopers.domain.coupon.discount.CouponDiscountStrategyFactory;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.infrastructure.paymentgateway.PaymentGatewayClient;
import com.loopers.infrastructure.paymentgateway.PaymentGatewayDto;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import feign.FeignException;
import feign.Request;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 구매 파사드.
 * <p>
 * 주문 생성과 결제(포인트 차감), 재고 조정, 외부 연동을 조율한다.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PurchasingFacade {

    /**
     * CircuitBreaker가 Open 상태일 때 Fallback이 반환하는 에러 코드.
     * <p>
     * 이 에러 코드는 외부 시스템 장애를 나타내며, 비즈니스 실패가 아닙니다.
     * 따라서 주문을 취소하지 않고 PENDING 상태로 유지하여 나중에 복구할 수 있도록 합니다.
     * </p>
     */
    private static final String ERROR_CODE_CIRCUIT_BREAKER_OPEN = "CIRCUIT_BREAKER_OPEN";

    private final UserRepository userRepository;
    private final UserJpaRepository userJpaRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponDiscountStrategyFactory couponDiscountStrategyFactory;
    private final PaymentGatewayClient paymentGatewayClient;

    /**
     * 주문을 생성한다.
     * <p>
     * 1. 사용자 조회 및 존재 여부 검증<br>
     * 2. 상품 재고 검증 및 차감<br>
     * 3. 사용자 포인트 검증 및 차감<br>
     * 4. 주문 저장<br>
     * 5. PG 결제 요청 (비동기)
     * </p>
     * <p>
     * <b>동시성 제어 전략:</b>
     * <ul>
     *   <li><b>PESSIMISTIC_WRITE 사용 근거:</b> Lost Update 방지 및 데이터 일관성 보장</li>
     *   <li><b>포인트 차감:</b> 동시 주문 시 포인트 중복 차감 방지 (금전적 손실 방지)</li>
     *   <li><b>재고 차감:</b> 동시 주문 시 재고 음수 방지 및 정확한 차감 보장 (재고 oversell 방지)</li>
     *   <li><b>Lock 범위 최소화:</b> PK/UNIQUE 인덱스 기반 조회로 Lock 범위 최소화</li>
     * </ul>
     * </p>
     * <p>
     * <b>DBA 설득 근거 (비관적 락 사용):</b>
     * <ul>
     *   <li><b>제한적 사용:</b> 전역이 아닌 금전적 손실 위험이 있는 특정 도메인에만 사용</li>
     *   <li><b>트랜잭션 최소화:</b> 트랜잭션 내부에 외부 I/O 없음, lock holding time 매우 짧음 (몇 ms)</li>
     *   <li><b>Lock 범위 최소화:</b> PK/UNIQUE 인덱스 기반 조회로 해당 행만 락 (Record Lock)</li>
     *   <li><b>애플리케이션 레벨 한계:</b> 애플리케이션 레벨로는 race condition을 완전히 방지할 수 없어서 DB 차원의 strong consistency 필요</li>
     *   <li><b>낙관적 락 기본 전략:</b> 쿠폰 사용은 낙관적 락 사용 (Hot Spot 대응)</li>
     * </ul>
     * </p>
     * <p>
     * <b>Lock 생명주기:</b>
     * <ol>
     *   <li>SELECT ... FOR UPDATE 실행 시 락 획득</li>
     *   <li>트랜잭션 내에서 락 유지 (외부 I/O 없음, 매우 짧은 시간)</li>
     *   <li>트랜잭션 커밋/롤백 시 락 자동 해제</li>
     * </ol>
     * </p>
     *
     * @param userId 사용자 식별자 (로그인 ID)
     * @param commands 주문 상품 정보
     * @param cardType 카드 타입 (SAMSUNG, KB, HYUNDAI)
     * @param cardNo 카드 번호 (xxxx-xxxx-xxxx-xxxx 형식)
     * @return 생성된 주문 정보
     */
    @Transactional
    public OrderInfo createOrder(String userId, List<OrderItemCommand> commands, String cardType, String cardNo) {
        if (userId == null || userId.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자 ID는 필수입니다.");
        }
        if (commands == null || commands.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 아이템은 1개 이상이어야 합니다.");
        }

        // 비관적 락을 사용하여 사용자 조회 (포인트 차감 시 동시성 제어)
        // - userId는 UNIQUE 인덱스가 있어 Lock 범위 최소화 (Record Lock만 적용)
        // - Lost Update 방지: 동시 주문 시 포인트 중복 차감 방지 (금전적 손실 방지)
        // - 트랜잭션 내부에 외부 I/O 없음, lock holding time 매우 짧음
        User user = loadUserForUpdate(userId);

        // ✅ Deadlock 방지: 상품 ID를 정렬하여 일관된 락 획득 순서 보장
        // 여러 상품을 주문할 때, 항상 동일한 순서로 락을 획득하여 deadlock 방지
        List<Long> sortedProductIds = commands.stream()
            .map(OrderItemCommand::productId)
            .distinct()
            .sorted()
            .toList();

        // 중복 상품 검증
        if (sortedProductIds.size() != commands.size()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품이 중복되었습니다.");
        }

        // 정렬된 순서대로 상품 락 획득 (Deadlock 방지)
        Map<Long, Product> productMap = new java.util.HashMap<>();

        for (Long productId : sortedProductIds) {
            // 비관적 락을 사용하여 상품 조회 (재고 차감 시 동시성 제어)
            // - id는 PK 인덱스가 있어 Lock 범위 최소화 (Record Lock만 적용)
            // - Lost Update 방지: 동시 주문 시 재고 음수 방지 및 정확한 차감 보장 (재고 oversell 방지)
            // - 트랜잭션 내부에 외부 I/O 없음, lock holding time 매우 짧음
            // - ✅ 정렬된 순서로 락 획득하여 deadlock 방지
            Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId)));
            productMap.put(productId, product);
        }

        // OrderItem 생성
        List<Product> products = new ArrayList<>();
        List<OrderItem> orderItems = new ArrayList<>();
        for (OrderItemCommand command : commands) {
            Product product = productMap.get(command.productId());
            products.add(product);

            orderItems.add(OrderItem.of(
                product.getId(),
                product.getName(),
                product.getPrice(),
                command.quantity()
            ));
        }

        // 쿠폰 처리 (있는 경우)
        String couponCode = extractCouponCode(commands);
        Integer discountAmount = 0;
        if (couponCode != null && !couponCode.isBlank()) {
            discountAmount = applyCoupon(user.getId(), couponCode, calculateSubtotal(orderItems));
        }

        Order order = Order.of(user.getId(), orderItems, couponCode, discountAmount);
        // 주문은 PENDING 상태로 생성됨 (Order 생성자에서 기본값으로 설정)
        // 결제 성공 후에만 COMPLETED로 변경됨

        decreaseStocksForOrderItems(order.getItems(), products);
        deductUserPoint(user, order.getTotalAmount());
        // 주문은 PENDING 상태로 유지 (결제 요청 중 상태)
        // 결제 성공 시 콜백이나 상태 확인 API를 통해 COMPLETED로 변경됨

        products.forEach(productRepository::save);
        userRepository.save(user);

        Order savedOrder = orderRepository.save(order);
        // 주문은 PENDING 상태로 저장됨

        // PG 결제 요청 (비동기)
        // 성공 시 transactionKey를 저장하여 나중에 상태 확인 가능하도록 함
        // 실패 시에도 주문은 PENDING 상태로 유지되어 나중에 복구 가능
        try {
            String transactionKey = requestPaymentToGateway(userId, savedOrder.getId(), cardType, cardNo, savedOrder.getTotalAmount());
            if (transactionKey != null) {
                // TODO: 주문에 transactionKey를 저장하는 필드가 있다면 저장
                // 현재는 주문 ID로 PG에서 결제 정보를 조회할 수 있으므로 일단 로그만 기록
                log.info("PG 결제 요청 완료. (orderId: {}, transactionKey: {})", savedOrder.getId(), transactionKey);
            } else {
                // PG 요청 실패: 외부 시스템 장애로 간주
                // 주문은 PENDING 상태로 유지되어 나중에 상태 확인 API나 콜백으로 복구 가능
                log.info("PG 결제 요청 실패. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", savedOrder.getId());
            }
        } catch (Exception e) {
            // PG 요청 중 예외 발생 시에도 주문은 이미 저장되어 있으므로 유지
            // 외부 시스템 장애는 내부 시스템에 영향을 주지 않도록 함
            log.error("PG 결제 요청 중 예외 발생. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", 
                savedOrder.getId(), e);
        }

        return OrderInfo.from(savedOrder);
    }

    /**
     * 주문을 취소하고 포인트를 환불하며 재고를 원복한다.
     * <p>
     * <b>동시성 제어:</b>
     * <ul>
     *   <li><b>비관적 락 사용:</b> 재고 원복 시 동시성 제어를 위해 findByIdForUpdate 사용</li>
     *   <li><b>Deadlock 방지:</b> 상품 ID를 정렬하여 일관된 락 획득 순서 보장</li>
     * </ul>
     * </p>
     *
     * @param order 주문 엔티티
     * @param user 사용자 엔티티
     */
    @Transactional
    public void cancelOrder(Order order, User user) {
        if (order == null || user == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "취소할 주문과 사용자 정보는 필수입니다.");
        }

        // ✅ Deadlock 방지: User 락을 먼저 획득하여 createOrder와 동일한 락 획득 순서 보장
        // createOrder: User 락 → Product 락 (정렬됨)
        // cancelOrder: User 락 → Product 락 (정렬됨) - 동일한 순서로 락 획득
        User lockedUser = userRepository.findByUserIdForUpdate(user.getUserId());
        if (lockedUser == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }

        // ✅ Deadlock 방지: 상품 ID를 정렬하여 일관된 락 획득 순서 보장
        List<Long> sortedProductIds = order.getItems().stream()
            .map(OrderItem::getProductId)
            .distinct()
            .sorted()
            .toList();

        // 정렬된 순서대로 상품 락 획득 (Deadlock 방지)
        Map<Long, Product> productMap = new java.util.HashMap<>();
        for (Long productId : sortedProductIds) {
            Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", productId)));
            productMap.put(productId, product);
        }

        // OrderItem 순서대로 Product 리스트 생성
        List<Product> products = order.getItems().stream()
            .map(item -> productMap.get(item.getProductId()))
            .toList();

        order.cancel();
        increaseStocksForOrderItems(order.getItems(), products);
        lockedUser.receivePoint(Point.of((long) order.getTotalAmount()));

        products.forEach(productRepository::save);
        userRepository.save(lockedUser);
        orderRepository.save(order);
    }

    /**
     * 사용자 ID로 주문 목록을 조회한다.
     *
     * @param userId 사용자 식별자 (로그인 ID)
     * @return 주문 목록
     */
    @Transactional(readOnly = true)
    public List<OrderInfo> getOrders(String userId) {
        User user = loadUser(userId);
        List<Order> orders = orderRepository.findAllByUserId(user.getId());
        return orders.stream()
            .map(OrderInfo::from)
            .toList();
    }

    /**
     * 주문 ID로 단일 주문을 조회한다.
     *
     * @param userId 사용자 식별자 (로그인 ID)
     * @param orderId 주문 ID
     * @return 주문 정보
     */
    @Transactional(readOnly = true)
    public OrderInfo getOrder(String userId, Long orderId) {
        User user = loadUser(userId);
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));

        if (!order.getUserId().equals(user.getId())) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }

        return OrderInfo.from(order);
    }

    private void decreaseStocksForOrderItems(List<OrderItem> items, List<Product> products) {
        Map<Long, Product> productMap = products.stream()
            .collect(Collectors.toMap(Product::getId, product -> product));

        for (OrderItem item : items) {
            Product product = productMap.get(item.getProductId());
            if (product == null) {
                throw new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", item.getProductId()));
            }
            product.decreaseStock(item.getQuantity());
        }
    }

    private void increaseStocksForOrderItems(List<OrderItem> items, List<Product> products) {
        Map<Long, Product> productMap = products.stream()
            .collect(Collectors.toMap(Product::getId, product -> product));

        for (OrderItem item : items) {
            Product product = productMap.get(item.getProductId());
            if (product == null) {
                throw new CoreException(ErrorType.NOT_FOUND,
                    String.format("상품을 찾을 수 없습니다. (상품 ID: %d)", item.getProductId()));
            }
            product.increaseStock(item.getQuantity());
        }
    }

    private void deductUserPoint(User user, Integer totalAmount) {
        if (Objects.requireNonNullElse(totalAmount, 0) <= 0) {
            return;
        }
        user.deductPoint(Point.of(totalAmount.longValue()));
    }

    private User loadUser(String userId) {
        User user = userRepository.findByUserId(userId);
        if (user == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return user;
    }

    /**
     * 비관적 락을 사용하여 사용자를 조회합니다.
     * <p>
     * 포인트 차감 등 동시성 제어가 필요한 경우 사용합니다.
     * </p>
     * <p>
     * <b>전제 조건:</b> userId는 상위 계층에서 이미 null/blank 검증이 완료되어야 합니다.
     * </p>
     *
     * @param userId 사용자 ID (null이 아니고 비어있지 않아야 함)
     * @return 조회된 사용자
     * @throws CoreException 사용자를 찾을 수 없는 경우
     */
    private User loadUserForUpdate(String userId) {
        User user = userRepository.findByUserIdForUpdate(userId);
        if (user == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return user;
    }

    /**
     * 주문 명령에서 쿠폰 코드를 추출합니다.
     *
     * @param commands 주문 명령 목록
     * @return 쿠폰 코드 (없으면 null)
     */
    private String extractCouponCode(List<OrderItemCommand> commands) {
        return commands.stream()
            .filter(cmd -> cmd.couponCode() != null && !cmd.couponCode().isBlank())
            .map(OrderItemCommand::couponCode)
            .findFirst()
            .orElse(null);
    }

    /**
     * 쿠폰을 적용하여 할인 금액을 계산하고 쿠폰을 사용 처리합니다.
     * <p>
     * <b>동시성 제어 전략:</b>
     * <ul>
     *   <li><b>OPTIMISTIC_LOCK 사용 근거:</b> 쿠폰 중복 사용 방지, Hot Spot 대응</li>
     *   <li><b>@Version 필드:</b> UserCoupon 엔티티의 version 필드를 통해 자동으로 낙관적 락 적용</li>
     *   <li><b>동시 사용 시:</b> 한 명만 성공하고 나머지는 OptimisticLockException 발생</li>
     *   <li><b>사용 목적:</b> 동일 쿠폰으로 여러 기기에서 동시 주문해도 한 번만 사용되도록 보장</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID
     * @param couponCode 쿠폰 코드
     * @param subtotal 주문 소계 금액
     * @return 할인 금액
     * @throws CoreException 쿠폰을 찾을 수 없거나 사용 불가능한 경우, 동시 사용으로 인한 충돌 시
     */
    private Integer applyCoupon(Long userId, String couponCode, Integer subtotal) {
        // 쿠폰 존재 여부 확인
        Coupon coupon = couponRepository.findByCode(couponCode)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                String.format("쿠폰을 찾을 수 없습니다. (쿠폰 코드: %s)", couponCode)));

        // 낙관적 락을 사용하여 사용자 쿠폰 조회 (동시성 제어)
        // @Version 필드가 있어 자동으로 낙관적 락이 적용됨
        UserCoupon userCoupon = userCouponRepository.findByUserIdAndCouponCodeForUpdate(userId, couponCode)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND,
                String.format("사용자가 소유한 쿠폰을 찾을 수 없습니다. (쿠폰 코드: %s)", couponCode)));

        // 쿠폰 사용 가능 여부 확인
        if (!userCoupon.isAvailable()) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                String.format("이미 사용된 쿠폰입니다. (쿠폰 코드: %s)", couponCode));
        }

        // 쿠폰 사용 처리
        userCoupon.use();

        // 할인 금액 계산 (전략 패턴 사용)
        Integer discountAmount = coupon.calculateDiscountAmount(subtotal, couponDiscountStrategyFactory);

        try {
            // 사용자 쿠폰 저장 (version 체크 자동 수행)
            // 다른 트랜잭션이 먼저 수정했다면 OptimisticLockException 발생
            userCouponRepository.save(userCoupon);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 낙관적 락 충돌: 다른 트랜잭션이 먼저 쿠폰을 사용함
            throw new CoreException(ErrorType.CONFLICT,
                String.format("쿠폰이 이미 사용되었습니다. (쿠폰 코드: %s)", couponCode));
        }

        return discountAmount;
    }

    /**
     * 주문 아이템 목록으로부터 소계 금액을 계산합니다.
     *
     * @param orderItems 주문 아이템 목록
     * @return 계산된 소계 금액
     */
    private Integer calculateSubtotal(List<OrderItem> orderItems) {
        return orderItems.stream()
            .mapToInt(item -> item.getPrice() * item.getQuantity())
            .sum();
    }

    /**
     * PG 결제 게이트웨이에 결제 요청을 전송합니다.
     * <p>
     * 주문 저장 후 비동기로 PG 시스템에 결제 요청을 전송합니다.
     * 실패 시에도 주문은 이미 저장되어 있으므로, 로그만 기록합니다.
     * </p>
     * <p>
     * <b>예외 처리 전략:</b>
     * <ul>
     *   <li><b>타임아웃:</b> FeignException.TimeoutException, SocketTimeoutException 처리</li>
     *   <li><b>연결 실패:</b> FeignException.ConnectException 처리</li>
     *   <li><b>서버 오류:</b> FeignException (4xx, 5xx) 처리</li>
     *   <li><b>기타 예외:</b> 일반 Exception 처리</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @param cardType 카드 타입
     * @param cardNo 카드 번호
     * @param amount 결제 금액
     * @return transactionKey (성공 시), null (실패 시)
     */
    private String requestPaymentToGateway(String userId, Long orderId, String cardType, String cardNo, Integer amount) {
        try {
            // 카드 타입 변환
            PaymentGatewayDto.CardType gatewayCardType;
            try {
                gatewayCardType = PaymentGatewayDto.CardType.valueOf(cardType.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("잘못된 카드 타입입니다. (orderId: {}, cardType: {})", orderId, cardType);
                return;
            }

            // 콜백 URL 생성 (주문 ID 기반)
            String callbackUrl = String.format("http://localhost:8080/api/v1/orders/%d/callback", orderId);

            // PG 결제 요청
            PaymentGatewayDto.PaymentRequest request = new PaymentGatewayDto.PaymentRequest(
                String.valueOf(orderId),
                gatewayCardType,
                cardNo,
                amount.longValue(),
                callbackUrl
            );

            PaymentGatewayDto.ApiResponse<PaymentGatewayDto.TransactionResponse> response =
                paymentGatewayClient.requestPayment(userId, request);

            if (response != null && response.meta() != null
                && response.meta().result() == PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS
                && response.data() != null) {
                String transactionKey = response.data().transactionKey();
                log.info("PG 결제 요청 성공. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
                return transactionKey;
            } else {
                // PG 결제 요청 실패 응답 처리
                String errorCode = response != null && response.meta() != null
                    ? response.meta().errorCode() : "UNKNOWN";
                String message = response != null && response.meta() != null
                    ? response.meta().message() : "응답이 null입니다.";
                log.warn("PG 결제 요청 실패. (orderId: {}, errorCode: {}, message: {})",
                    orderId, errorCode, message);
                
                // CircuitBreaker Open 상태는 외부 시스템 장애로 간주
                // Fallback이 호출된 경우이므로 주문을 PENDING 상태로 유지
                if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
                    log.info("CircuitBreaker가 Open 상태입니다. Fallback이 호출되었습니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
                    return null; // 주문은 PENDING 상태로 유지
                }
                
                // 명확한 비즈니스 실패만 주문 취소 (예: 카드 한도 초과, 잘못된 카드)
                // 외부 시스템 장애나 일시적 오류는 주문을 PENDING 상태로 유지하여 나중에 복구 가능하도록 함
                if (isBusinessFailure(errorCode)) {
                    handlePaymentFailure(userId, orderId, errorCode, message);
                } else {
                    // 외부 시스템 장애: 주문은 PENDING 상태로 유지, 나중에 상태 확인 API로 복구 가능
                    log.info("외부 시스템 장애로 인한 실패. 주문은 PENDING 상태로 유지됩니다. (orderId: {}, errorCode: {})",
                        orderId, errorCode);
                }
                return null;
            }
        } catch (FeignException.TimeoutException e) {
            // 타임아웃 예외 처리
            Request request = e.request();
            String method = request != null ? request.httpMethod().name() : "UNKNOWN";
            String url = request != null ? request.url() : "UNKNOWN";
            log.error("PG 결제 요청 타임아웃 발생. (orderId: {}, method: {}, url: {})",
                orderId, method, url, e);
            
            // 타임아웃 발생 시에도 PG에서 실제 결제 상태를 확인하여 반영
            // 타임아웃은 요청이 전송되었을 수 있으므로, 실제 결제 상태를 확인해야 함
            log.info("타임아웃 발생. PG 결제 상태 확인 API를 호출하여 실제 결제 상태를 확인합니다. (orderId: {})", orderId);
            checkAndRecoverPaymentStatusAfterTimeout(userId, orderId);
            return null;
        } catch (FeignException e) {
            // Feign 예외 처리 (연결 실패, 서버 오류 등)
            Request request = e.request();
            int status = e.status();
            String method = request != null ? request.httpMethod().name() : "UNKNOWN";
            String url = request != null ? request.url() : "UNKNOWN";
            
            if (status >= 500) {
                // 서버 오류 (5xx): 외부 시스템 장애로 간주
                log.error("PG 서버 오류 발생. (orderId: {}, status: {}, method: {}, url: {})",
                    orderId, status, method, url, e);
                // 서버 오류는 외부 시스템 장애: 주문은 PENDING 상태로 유지
                log.info("서버 오류로 인한 실패. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
            } else if (status >= 400) {
                // 클라이언트 오류 (4xx): 일부는 비즈니스 실패, 일부는 외부 시스템 장애
                log.warn("PG 클라이언트 오류 발생. (orderId: {}, status: {}, method: {}, url: {})",
                    orderId, status, method, url, e);
                // 400 Bad Request는 비즈니스 실패로 간주할 수 있지만, 
                // 외부 시스템 장애일 수도 있으므로 주문은 유지 (나중에 복구 가능)
                if (status == 400) {
                    // 400은 비즈니스 실패 가능성이 높지만, 안전하게 주문 유지
                    log.info("클라이언트 오류(400) 발생. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
                } else {
                    // 401, 403 등은 외부 시스템 장애로 간주
                    log.info("클라이언트 오류({}) 발생. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", 
                        status, orderId);
                }
            } else {
                // 기타 Feign 예외: 외부 시스템 장애로 간주
                log.error("PG 결제 요청 중 Feign 예외 발생. (orderId: {}, status: {})",
                    orderId, status, e);
                log.info("Feign 예외 발생. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
            }
            return null;
        } catch (Exception e) {
            // 기타 예외 처리
            // SocketTimeoutException 등도 여기서 처리됨
            if (e.getCause() instanceof SocketTimeoutException) {
                log.error("PG 결제 요청 소켓 타임아웃 발생. (orderId: {})", orderId, e);
                // 소켓 타임아웃 발생 시에도 PG에서 실제 결제 상태를 확인하여 반영
                log.info("소켓 타임아웃 발생. PG 결제 상태 확인 API를 호출하여 실제 결제 상태를 확인합니다. (orderId: {})", orderId);
                checkAndRecoverPaymentStatusAfterTimeout(userId, orderId);
            } else {
                log.error("PG 결제 요청 중 예상치 못한 오류 발생. (orderId: {})", orderId, e);
                // 예상치 못한 오류도 외부 시스템 장애로 간주: 주문은 PENDING 상태로 유지
                log.info("예상치 못한 오류 발생. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
            }
            return null;
        }
    }

    /**
     * 타임아웃 발생 후 PG 결제 상태를 확인하여 시스템에 반영합니다.
     * <p>
     * 타임아웃은 요청이 전송되었을 수 있으므로, 실제 결제 상태를 확인하여
     * 결제가 성공했다면 주문을 완료하고, 실패했다면 주문을 취소합니다.
     * </p>
     * <p>
     * <b>처리 전략:</b>
     * <ul>
     *   <li>타임아웃 발생 직후 즉시 상태 확인 API 호출</li>
     *   <li>결제 상태에 따라 주문 상태 업데이트</li>
     *   <li>상태 확인 실패 시에도 주문은 PENDING 상태로 유지 (나중에 스케줄러로 복구)</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     */
    private void checkAndRecoverPaymentStatusAfterTimeout(String userId, Long orderId) {
        try {
            // 잠시 대기 후 상태 확인 (PG 처리 시간 고려)
            // 타임아웃이 발생했지만 요청은 전송되었을 수 있으므로, 
            // PG 시스템이 처리할 시간을 주기 위해 짧은 대기
            Thread.sleep(1000); // 1초 대기
            
            // PG에서 주문별 결제 정보 조회
            PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
                paymentGatewayClient.getTransactionsByOrder(userId, String.valueOf(orderId));
            
            if (response == null || response.meta() == null
                || response.meta().result() != PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS
                || response.data() == null || response.data().transactions() == null
                || response.data().transactions().isEmpty()) {
                // PG에서 결제 정보를 찾을 수 없음: 아직 처리 중이거나 요청이 전송되지 않았을 수 있음
                log.info("타임아웃 후 상태 확인: PG에서 결제 정보를 찾을 수 없습니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {})", orderId);
                return;
            }
            
            // 가장 최근 트랜잭션의 상태 확인
            PaymentGatewayDto.TransactionResponse latestTransaction = 
                response.data().transactions().get(response.data().transactions().size() - 1);
            
            PaymentGatewayDto.TransactionStatus status = latestTransaction.status();
            
            // 별도 트랜잭션으로 상태 업데이트
            updateOrderStatusByPaymentStatus(orderId, status, latestTransaction.transactionKey(), latestTransaction.reason());
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("타임아웃 후 상태 확인 중 인터럽트 발생. (orderId: {})", orderId);
        } catch (FeignException e) {
            // PG 상태 확인 API 호출 실패: 나중에 스케줄러로 복구 가능
            log.warn("타임아웃 후 상태 확인 API 호출 실패. 나중에 스케줄러로 복구됩니다. (orderId: {})", orderId, e);
        } catch (Exception e) {
            // 기타 오류: 나중에 스케줄러로 복구 가능
            log.error("타임아웃 후 상태 확인 중 오류 발생. 나중에 스케줄러로 복구됩니다. (orderId: {})", orderId, e);
        }
    }

    /**
     * 결제 상태에 따라 주문 상태를 업데이트합니다.
     * <p>
     * 별도 트랜잭션으로 실행하여 외부 시스템 호출과 독립적으로 처리합니다.
     * </p>
     *
     * @param orderId 주문 ID
     * @param status 결제 상태
     * @param transactionKey 트랜잭션 키
     * @param reason 실패 사유 (실패 시)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOrderStatusByPaymentStatus(Long orderId, PaymentGatewayDto.TransactionStatus status, 
                                                  String transactionKey, String reason) {
        try {
            // 주문 조회
            Order order = orderRepository.findById(orderId)
                .orElse(null);
            
            if (order == null) {
                log.warn("주문 상태 업데이트 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
                return;
            }
            
            // 이미 완료되거나 취소된 주문인 경우 처리하지 않음
            if (order.getStatus() == OrderStatus.COMPLETED) {
                log.info("이미 완료된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return;
            }
            
            if (order.getStatus() == OrderStatus.CANCELED) {
                log.info("이미 취소된 주문입니다. 상태 업데이트를 건너뜁니다. (orderId: {})", orderId);
                return;
            }
            
            if (status == PaymentGatewayDto.TransactionStatus.SUCCESS) {
                // 결제 성공: 주문 완료
                order.complete();
                orderRepository.save(order);
                log.info("타임아웃 후 상태 확인 결과, 주문 상태를 COMPLETED로 업데이트했습니다. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
            } else if (status == PaymentGatewayDto.TransactionStatus.FAILED) {
                // 결제 실패: 주문 취소 및 리소스 원복
                User user = userJpaRepository.findById(order.getUserId())
                    .orElse(null);
                if (user == null) {
                    log.warn("주문 상태 업데이트 시 사용자를 찾을 수 없습니다. (orderId: {}, userId: {})",
                        orderId, order.getUserId());
                    return;
                }
                cancelOrder(order, user);
                log.info("타임아웃 후 상태 확인 결과, 주문 상태를 CANCELED로 업데이트했습니다. (orderId: {}, transactionKey: {}, reason: {})",
                    orderId, transactionKey, reason);
            } else {
                // PENDING 상태: 아직 처리 중
                log.info("타임아웃 후 상태 확인 결과, 아직 처리 중입니다. 주문은 PENDING 상태로 유지됩니다. (orderId: {}, transactionKey: {})",
                    orderId, transactionKey);
            }
        } catch (Exception e) {
            log.error("주문 상태 업데이트 중 오류 발생. (orderId: {})", orderId, e);
            // 예외 발생 시에도 로그만 기록 (나중에 스케줄러로 복구 가능)
        }
    }

    /**
     * 오류 코드가 명확한 비즈니스 실패인지 확인합니다.
     * <p>
     * 비즈니스 실패는 주문을 취소해야 하지만,
     * 외부 시스템 장애는 주문을 PENDING 상태로 유지하여 나중에 복구할 수 있도록 합니다.
     * </p>
     * <p>
     * <b>비즈니스 실패 예시:</b>
     * <ul>
     *   <li>카드 한도 초과 (LIMIT_EXCEEDED)</li>
     *   <li>잘못된 카드 번호 (INVALID_CARD)</li>
     *   <li>카드 오류 (CARD_ERROR)</li>
     *   <li>잔액 부족 (INSUFFICIENT_FUNDS)</li>
     * </ul>
     * </p>
     * <p>
     * <b>외부 시스템 장애 예시:</b>
     * <ul>
     *   <li>CircuitBreaker Open (CIRCUIT_BREAKER_OPEN)</li>
     *   <li>서버 오류 (5xx)</li>
     *   <li>타임아웃</li>
     *   <li>네트워크 오류</li>
     * </ul>
     * </p>
     *
     * @param errorCode 오류 코드
     * @return 비즈니스 실패인 경우 true, 외부 시스템 장애인 경우 false
     */
    private boolean isBusinessFailure(String errorCode) {
        // null 체크
        if (errorCode == null) {
            return false;
        }
        
        // CircuitBreaker Open 상태는 명시적으로 외부 시스템 장애로 간주
        if (ERROR_CODE_CIRCUIT_BREAKER_OPEN.equals(errorCode)) {
            return false;
        }
        
        // 명확한 비즈니스 실패 오류 코드만 취소 처리
        // 예: 카드 한도 초과, 잘못된 카드 번호 등
        return errorCode.contains("LIMIT_EXCEEDED") ||
            errorCode.contains("INVALID_CARD") ||
            errorCode.contains("CARD_ERROR") ||
            errorCode.contains("INSUFFICIENT_FUNDS") ||
            errorCode.contains("PAYMENT_FAILED"); // 명시적인 결제 실패도 비즈니스 실패로 간주
    }

    /**
     * PG 결제 콜백을 처리합니다.
     * <p>
     * PG 시스템에서 결제 처리 완료 후 콜백으로 전송된 결제 결과를 받아
     * 주문 상태를 업데이트합니다.
     * </p>
     * <p>
     * <b>처리 내용:</b>
     * <ul>
     *   <li>결제 성공 (SUCCESS): 주문 상태를 COMPLETED로 변경</li>
     *   <li>결제 실패 (FAILED): 주문 상태를 CANCELED로 변경하고 리소스 원복</li>
     *   <li>결제 대기 (PENDING): 상태 유지 (추가 처리 없음)</li>
     * </ul>
     * </p>
     *
     * @param orderId 주문 ID
     * @param callbackRequest 콜백 요청 정보
     */
    @Transactional
    public void handlePaymentCallback(Long orderId, PaymentGatewayDto.CallbackRequest callbackRequest) {
        try {
            // 주문 조회
            Order order = orderRepository.findById(orderId)
                .orElse(null);
            
            if (order == null) {
                log.warn("콜백 처리 시 주문을 찾을 수 없습니다. (orderId: {}, transactionKey: {})",
                    orderId, callbackRequest.transactionKey());
                return;
            }
            
            // 이미 완료되거나 취소된 주문인 경우 처리하지 않음
            if (order.getStatus() == OrderStatus.COMPLETED) {
                log.info("이미 완료된 주문입니다. 콜백 처리를 건너뜁니다. (orderId: {}, transactionKey: {})",
                    orderId, callbackRequest.transactionKey());
                return;
            }
            
            if (order.getStatus() == OrderStatus.CANCELED) {
                log.info("이미 취소된 주문입니다. 콜백 처리를 건너뜁니다. (orderId: {}, transactionKey: {})",
                    orderId, callbackRequest.transactionKey());
                return;
            }
            
            // 결제 상태에 따른 처리
            PaymentGatewayDto.TransactionStatus status = callbackRequest.status();
            if (status == PaymentGatewayDto.TransactionStatus.SUCCESS) {
                // 결제 성공: 주문 완료
                order.complete();
                orderRepository.save(order);
                log.info("PG 결제 성공 콜백 처리 완료. (orderId: {}, transactionKey: {})",
                    orderId, callbackRequest.transactionKey());
            } else if (status == PaymentGatewayDto.TransactionStatus.FAILED) {
                // 결제 실패: 주문 취소 및 리소스 원복
                User user = loadUser(order.getUserId());
                cancelOrder(order, user);
                log.info("PG 결제 실패 콜백 처리 완료. (orderId: {}, transactionKey: {}, reason: {})",
                    orderId, callbackRequest.transactionKey(), callbackRequest.reason());
            } else {
                // PENDING 상태: 아직 처리 중이므로 상태 유지
                log.info("PG 결제 대기 중 콜백 수신. (orderId: {}, transactionKey: {})",
                    orderId, callbackRequest.transactionKey());
            }
        } catch (Exception e) {
            log.error("콜백 처리 중 오류 발생. (orderId: {}, transactionKey: {})",
                orderId, callbackRequest.transactionKey(), e);
            throw e; // 콜백 실패는 재시도 가능하도록 예외를 다시 던짐
        }
    }

    /**
     * 결제 상태 확인 API를 통해 주문 상태를 복구합니다.
     * <p>
     * 콜백이 오지 않았거나 타임아웃된 경우, PG 시스템의 결제 상태 확인 API를 호출하여
     * 실제 결제 상태를 확인하고 주문 상태를 업데이트합니다.
     * </p>
     * <p>
     * <b>처리 내용:</b>
     * <ul>
     *   <li>주문 ID로 PG에서 결제 정보 조회</li>
     *   <li>결제 상태에 따라 주문 상태 업데이트</li>
     *   <li>결제 성공 시 주문 완료, 실패 시 주문 취소 및 리소스 원복</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID (String - PG API 요구사항)
     * @param orderId 주문 ID
     */
    @Transactional
    public void recoverOrderStatusByPaymentCheck(String userId, Long orderId) {
        try {
            // 주문 조회
            Order order = orderRepository.findById(orderId)
                .orElse(null);
            
            if (order == null) {
                log.warn("상태 복구 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
                return;
            }
            
            // 이미 완료되거나 취소된 주문인 경우 처리하지 않음
            if (order.getStatus() == OrderStatus.COMPLETED) {
                log.info("이미 완료된 주문입니다. 상태 복구를 건너뜁니다. (orderId: {})", orderId);
                return;
            }
            
            if (order.getStatus() == OrderStatus.CANCELED) {
                log.info("이미 취소된 주문입니다. 상태 복구를 건너뜁니다. (orderId: {})", orderId);
                return;
            }
            
            // PG에서 주문별 결제 정보 조회
            PaymentGatewayDto.ApiResponse<PaymentGatewayDto.OrderResponse> response =
                paymentGatewayClient.getTransactionsByOrder(userId, String.valueOf(orderId));
            
            if (response == null || response.meta() == null
                || response.meta().result() != PaymentGatewayDto.ApiResponse.Metadata.Result.SUCCESS
                || response.data() == null || response.data().transactions() == null
                || response.data().transactions().isEmpty()) {
                log.warn("PG에서 결제 정보를 찾을 수 없습니다. (orderId: {})", orderId);
                return;
            }
            
            // 가장 최근 트랜잭션의 상태 확인
            PaymentGatewayDto.TransactionResponse latestTransaction = 
                response.data().transactions().get(response.data().transactions().size() - 1);
            
            PaymentGatewayDto.TransactionStatus status = latestTransaction.status();
            
            if (status == PaymentGatewayDto.TransactionStatus.SUCCESS) {
                // 결제 성공: 주문 완료
                order.complete();
                orderRepository.save(order);
                log.info("결제 상태 확인 결과, 주문 상태를 COMPLETED로 복구했습니다. (orderId: {}, transactionKey: {})",
                    orderId, latestTransaction.transactionKey());
            } else if (status == PaymentGatewayDto.TransactionStatus.FAILED) {
                // 결제 실패: 주문 취소 및 리소스 원복
                // Order의 userId는 Long이므로 UserJpaRepository를 사용하여 조회
                User user = userJpaRepository.findById(order.getUserId())
                    .orElse(null);
                if (user == null) {
                    log.warn("상태 복구 시 사용자를 찾을 수 없습니다. (orderId: {}, userId: {})",
                        orderId, order.getUserId());
                    return;
                }
                cancelOrder(order, user);
                log.info("결제 상태 확인 결과, 주문 상태를 CANCELED로 복구했습니다. (orderId: {}, transactionKey: {}, reason: {})",
                    orderId, latestTransaction.transactionKey(), latestTransaction.reason());
            } else {
                // PENDING 상태: 아직 처리 중
                log.info("결제 상태 확인 결과, 아직 처리 중입니다. (orderId: {}, transactionKey: {})",
                    orderId, latestTransaction.transactionKey());
            }
        } catch (FeignException e) {
            log.error("결제 상태 확인 API 호출 중 오류 발생. (orderId: {})", orderId, e);
            // API 호출 실패는 로그만 기록하고 예외를 던지지 않음 (수동 복구 가능하도록)
        } catch (Exception e) {
            log.error("상태 복구 중 오류 발생. (orderId: {})", orderId, e);
            // 기타 오류도 로그만 기록
        }
    }

    /**
     * 결제 실패 시 주문 취소 및 리소스 원복을 처리합니다.
     * <p>
     * 결제 요청이 실패한 경우, 이미 생성된 주문을 취소하고
     * 차감된 포인트를 환불하며 재고를 원복합니다.
     * </p>
     * <p>
     * <b>처리 내용:</b>
     * <ul>
     *   <li>주문 상태를 CANCELED로 변경</li>
     *   <li>차감된 포인트 환불</li>
     *   <li>차감된 재고 원복</li>
     * </ul>
     * </p>
     * <p>
     * <b>트랜잭션 전략:</b>
     * <ul>
     *   <li>REQUIRES_NEW: 별도 트랜잭션으로 실행하여 외부 시스템 호출과 독립적으로 처리</li>
     *   <li>결제 실패 처리 중 오류가 발생해도 기존 주문 생성 트랜잭션에 영향을 주지 않음</li>
     * </ul>
     * </p>
     * <p>
     * <b>주의사항:</b>
     * <ul>
     *   <li>주문이 이미 취소되었거나 존재하지 않는 경우 로그만 기록합니다.</li>
     *   <li>결제 실패 처리 중 오류 발생 시에도 로그만 기록합니다.</li>
     * </ul>
     * </p>
     *
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @param errorCode 오류 코드
     * @param errorMessage 오류 메시지
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePaymentFailure(String userId, Long orderId, String errorCode, String errorMessage) {
        try {
            // 사용자 조회
            User user = loadUser(userId);
            
            // 주문 조회
            Order order = orderRepository.findById(orderId)
                .orElse(null);
            
            if (order == null) {
                log.warn("결제 실패 처리 시 주문을 찾을 수 없습니다. (orderId: {})", orderId);
                return;
            }
            
            // 이미 취소된 주문인 경우 처리하지 않음
            if (order.getStatus() == OrderStatus.CANCELED) {
                log.info("이미 취소된 주문입니다. 결제 실패 처리를 건너뜁니다. (orderId: {})", orderId);
                return;
            }
            
            // 주문 취소 및 리소스 원복
            cancelOrder(order, user);
            
            log.info("결제 실패로 인한 주문 취소 완료. (orderId: {}, errorCode: {}, errorMessage: {})",
                orderId, errorCode, errorMessage);
        } catch (Exception e) {
            // 결제 실패 처리 중 오류 발생 시에도 로그만 기록
            // 이미 주문은 생성되어 있으므로, 나중에 수동으로 처리할 수 있도록 로그 기록
            log.error("결제 실패 처리 중 오류 발생. (orderId: {}, errorCode: {})",
                orderId, errorCode, e);
        }
    }
}

