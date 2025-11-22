package com.loopers.infrastructure.seeding;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Faker 기반 대량 데이터 생성을 위한 시딩 서비스.
 * <p>
 * 인덱스 및 캐시 성능 테스트를 위해 대량의 테스트 데이터를 생성합니다.
 * Faker 라이브러리를 활용하여 현실적이고 다양한 데이터를 생성합니다.
 * </p>
 *
 * @author Loopers
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSeedingService {

    private final DataSeedingConfig config;
    private final UserJpaRepository userJpaRepository;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final OrderJpaRepository orderJpaRepository;

    private final Random random = new Random();
    private Faker faker;

    /**
     * 설정을 반환합니다.
     *
     * @return 데이터 시딩 설정
     */
    public DataSeedingConfig getConfig() {
        return config;
    }

    /**
     * Faker 인스턴스를 초기화합니다.
     */
    private Faker getFaker() {
        if (faker == null) {
            Locale locale = Locale.forLanguageTag(config.getFakerLocale());
            faker = new Faker(locale);
        }
        return faker;
    }

    /**
     * 모든 데이터를 시딩합니다.
     */
    @Transactional
    public void seedAll() {
        log.info("=== 데이터 시딩 시작 (Faker 기반) ===");
        long startTime = System.currentTimeMillis();

        List<Long> userIds = seedUsers();
        List<Long> brandIds = seedBrands();
        List<Long> productIds = seedProducts(brandIds);
        seedLikes(userIds, productIds);
        seedOrders(userIds, productIds);

        long endTime = System.currentTimeMillis();
        log.info("=== 데이터 시딩 완료 (소요 시간: {}ms) ===", endTime - startTime);
    }

    /**
     * 사용자 데이터를 생성합니다.
     *
     * @return 생성된 사용자 ID 목록
     */
    @Transactional
    public List<Long> seedUsers() {
        log.info("사용자 데이터 생성 시작: {}명", config.getUserCount());
        long startTime = System.currentTimeMillis();

        List<User> users = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        Faker fakerInstance = getFaker();
        Set<String> usedUserIds = new HashSet<>(); // 중복 방지를 위한 Set

        for (int i = 1; i <= config.getUserCount(); i++) {
            // userId는 unique 제약조건이 있으므로 고유성을 보장해야 함
            // Faker의 username을 사용하되, 중복 방지를 위해 인덱스와 조합
            String userId;
            int attempts = 0;
            do {
                String rawUsername = fakerInstance.internet().username();
                String baseUserId = rawUsername.replaceAll("[^a-zA-Z0-9]", "");
                
                // 인덱스를 포함하여 고유성 보장 (최대 10자 제약 고려)
                // 예: "abc123" + "45" = "abc12345" (8자)
                String indexSuffix = String.valueOf(i);
                if (baseUserId.length() + indexSuffix.length() <= 10) {
                    userId = baseUserId + indexSuffix;
                } else {
                    // 길이가 초과하면 baseUserId를 자르고 인덱스 추가
                    int maxBaseLength = 10 - indexSuffix.length();
                    if (maxBaseLength > 0) {
                        userId = baseUserId.substring(0, Math.min(maxBaseLength, baseUserId.length())) + indexSuffix;
                    } else {
                        // 인덱스가 너무 길면 인덱스만 사용 (숫자만)
                        userId = indexSuffix.length() <= 10 ? indexSuffix : indexSuffix.substring(indexSuffix.length() - 10);
                    }
                }
                
                // 최소 길이 보장
                if (userId.length() < 3) {
                    userId = "u" + userId;
                    if (userId.length() > 10) {
                        userId = userId.substring(0, 10);
                    }
                }
                
                attempts++;
                if (attempts > 100) {
                    // 너무 많은 시도 후에는 인덱스 기반으로 생성
                    userId = "u" + String.format("%09d", i);
                    break;
                }
            } while (usedUserIds.contains(userId));
            
            usedUserIds.add(userId);
            // 이메일은 검증 패턴에 맞게 직접 구성
            // 패턴: ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$
            // Faker의 username을 사용하되 검증 패턴에 맞게 정제
            String emailUsername = fakerInstance.internet().username();
            String emailLocalPart = emailUsername
                .replaceAll("[^a-zA-Z0-9._%+-]", "") // 허용된 특수문자만 유지
                .replaceAll("^[._%+-]+", "") // 시작이 특수문자면 제거
                .replaceAll("[._%+-]+$", ""); // 끝이 특수문자면 제거
            if (emailLocalPart.isEmpty() || emailLocalPart.length() < 1) {
                emailLocalPart = "user" + i;
            }
            // 도메인은 안전한 형식으로 생성 (Faker domainName 사용 또는 기본 도메인)
            String emailDomain;
            try {
                String rawDomain = fakerInstance.internet().domainName();
                emailDomain = rawDomain.replaceAll("[^a-zA-Z0-9.-]", "");
                if (emailDomain.isEmpty() || !emailDomain.contains(".")) {
                    throw new IllegalArgumentException("Invalid domain");
                }
            } catch (Exception e) {
                // 기본 도메인 사용
                String[] safeDomains = {"example.com", "test.com", "sample.org", "demo.net"};
                emailDomain = safeDomains[random.nextInt(safeDomains.length)];
            }
            String email = emailLocalPart + "@" + emailDomain;
            // 생년월일: 18~80세 사이
            LocalDate birthLocalDate = fakerInstance.date().birthday(18, 80).toLocalDateTime().toLocalDate();
            String birthDate = birthLocalDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Gender gender = random.nextBoolean() ? Gender.MALE : Gender.FEMALE;
            long point = random.nextLong(0, 1_000_000L);

            User user = User.of(userId, email, birthDate, gender, Point.of(point));
            users.add(user);

            if (users.size() >= config.getBatchSize()) {
                List<User> saved = userJpaRepository.saveAll(users);
                userIds.addAll(saved.stream().map(User::getId).toList());
                users.clear();
                log.debug("사용자 {}명 저장 완료", config.getBatchSize());
            }
        }

        if (!users.isEmpty()) {
            List<User> saved = userJpaRepository.saveAll(users);
            userIds.addAll(saved.stream().map(User::getId).toList());
        }

        long endTime = System.currentTimeMillis();
        log.info("사용자 데이터 생성 완료: {}명 (소요 시간: {}ms)", userIds.size(), endTime - startTime);
        return userIds;
    }

    /**
     * 브랜드 데이터를 생성합니다.
     *
     * @return 생성된 브랜드 ID 목록
     */
    @Transactional
    public List<Long> seedBrands() {
        log.info("브랜드 데이터 생성 시작: {}개", config.getBrandCount());
        long startTime = System.currentTimeMillis();

        List<Brand> brands = new ArrayList<>();
        List<Long> brandIds = new ArrayList<>();
        Faker fakerInstance = getFaker();

        for (int i = 1; i <= config.getBrandCount(); i++) {
            // Faker를 사용하여 브랜드명 생성
            String brandName = fakerInstance.company().name();

            Brand brand = Brand.of(brandName);
            brands.add(brand);

            if (brands.size() >= config.getBatchSize()) {
                List<Brand> saved = brandJpaRepository.saveAll(brands);
                brandIds.addAll(saved.stream().map(Brand::getId).toList());
                brands.clear();
            }
        }

        if (!brands.isEmpty()) {
            List<Brand> saved = brandJpaRepository.saveAll(brands);
            brandIds.addAll(saved.stream().map(Brand::getId).toList());
        }

        long endTime = System.currentTimeMillis();
        log.info("브랜드 데이터 생성 완료: {}개 (소요 시간: {}ms)", brandIds.size(), endTime - startTime);
        return brandIds;
    }

    /**
     * 상품 데이터를 생성합니다.
     *
     * @param brandIds 브랜드 ID 목록 (null이면 기존 데이터에서 조회)
     * @return 생성된 상품 ID 목록
     */
    @Transactional
    public List<Long> seedProducts(List<Long> brandIds) {
        final List<Long> finalBrandIds;
        if (brandIds == null) {
            finalBrandIds = brandJpaRepository.findAll().stream()
                .map(Brand::getId)
                .toList();
            log.info("기존 브랜드 데이터 사용: {}개", finalBrandIds.size());
        } else {
            finalBrandIds = brandIds;
        }

        log.info("상품 데이터 생성 시작: {}개", config.getProductCount());
        long startTime = System.currentTimeMillis();

        List<Product> products = new ArrayList<>();
        List<Long> productIds = new ArrayList<>();
        Faker fakerInstance = getFaker();

        for (int i = 1; i <= config.getProductCount(); i++) {
            // Faker를 사용하여 상품명 생성
            String productName = fakerInstance.commerce().productName();

            int price = random.nextInt(1000, 1_000_000);
            int stock = random.nextInt(0, 10000);
            Long brandId = finalBrandIds.get(random.nextInt(finalBrandIds.size()));

            Product product = Product.of(productName, price, stock, brandId);
            products.add(product);

            if (products.size() >= config.getBatchSize()) {
                List<Product> saved = productJpaRepository.saveAll(products);
                productIds.addAll(saved.stream().map(Product::getId).toList());
                products.clear();
                log.debug("상품 {}개 저장 완료", config.getBatchSize());
            }
        }

        if (!products.isEmpty()) {
            List<Product> saved = productJpaRepository.saveAll(products);
            productIds.addAll(saved.stream().map(Product::getId).toList());
        }

        long endTime = System.currentTimeMillis();
        log.info("상품 데이터 생성 완료: {}개 (소요 시간: {}ms)", productIds.size(), endTime - startTime);
        return productIds;
    }

    /**
     * 좋아요 데이터를 생성합니다.
     *
     * @param userIds 사용자 ID 목록 (null이면 기존 데이터에서 조회)
     * @param productIds 상품 ID 목록 (null이면 기존 데이터에서 조회)
     */
    @Transactional
    public void seedLikes(List<Long> userIds, List<Long> productIds) {
        final List<Long> finalUserIds;
        if (userIds == null) {
            finalUserIds = userJpaRepository.findAll().stream()
                .map(User::getId)
                .toList();
            log.info("기존 사용자 데이터 사용: {}명", finalUserIds.size());
        } else {
            finalUserIds = userIds;
        }

        final List<Long> finalProductIds;
        if (productIds == null) {
            finalProductIds = productJpaRepository.findAllProductIds();
            log.info("기존 상품 데이터 사용: {}개", finalProductIds.size());
        } else {
            finalProductIds = productIds;
        }

        int totalLikes = finalUserIds.size() * config.getLikesPerUser();
        log.info("좋아요 데이터 생성 시작: 약 {}개 (사용자당 평균 {}개)", totalLikes, config.getLikesPerUser());
        long startTime = System.currentTimeMillis();

        List<Like> likes = new ArrayList<>();
        int likeCount = 0;

        for (Long userId : finalUserIds) {
            // 각 사용자마다 랜덤한 상품에 좋아요를 생성
            int userLikeCount = random.nextInt(config.getLikesPerUser() / 2, config.getLikesPerUser() * 2);
            Set<Long> likedProductIds = IntStream.range(0, userLikeCount)
                .mapToObj(i -> finalProductIds.get(random.nextInt(finalProductIds.size())))
                .collect(Collectors.toSet());

            for (Long productId : likedProductIds) {
                Like like = Like.of(userId, productId);
                likes.add(like);
                likeCount++;

                if (likes.size() >= config.getBatchSize()) {
                    likeJpaRepository.saveAll(likes);
                    likes.clear();
                    log.debug("좋아요 {}개 저장 완료", config.getBatchSize());
                }
            }
        }

        if (!likes.isEmpty()) {
            likeJpaRepository.saveAll(likes);
        }

        long endTime = System.currentTimeMillis();
        log.info("좋아요 데이터 생성 완료: {}개 (소요 시간: {}ms)", likeCount, endTime - startTime);
    }

    /**
     * 주문 데이터를 생성합니다.
     *
     * @param userIds 사용자 ID 목록 (null이면 기존 데이터에서 조회)
     * @param productIds 상품 ID 목록 (null이면 기존 데이터에서 조회)
     */
    @Transactional
    public void seedOrders(List<Long> userIds, List<Long> productIds) {
        final List<Long> finalUserIds;
        if (userIds == null) {
            finalUserIds = userJpaRepository.findAll().stream()
                .map(User::getId)
                .toList();
            log.info("기존 사용자 데이터 사용: {}명", finalUserIds.size());
        } else {
            finalUserIds = userIds;
        }

        final List<Long> finalProductIds;
        if (productIds == null) {
            finalProductIds = productJpaRepository.findAllProductIds();
            log.info("기존 상품 데이터 사용: {}개", finalProductIds.size());
        } else {
            finalProductIds = productIds;
        }

        log.info("주문 데이터 생성 시작: {}개", config.getOrderCount());
        long startTime = System.currentTimeMillis();

        List<Order> orders = new ArrayList<>();
        int orderCount = 0;
        Faker fakerInstance = getFaker();

        for (int i = 0; i < config.getOrderCount(); i++) {
            Long userId = finalUserIds.get(random.nextInt(finalUserIds.size()));
            OrderStatus status = getRandomOrderStatus();

            // 주문 아이템 생성 (1~5개)
            int itemCount = random.nextInt(1, 6);
            List<OrderItem> items = new ArrayList<>();

            for (int j = 0; j < itemCount; j++) {
                Long productId = finalProductIds.get(random.nextInt(finalProductIds.size()));
                // Faker를 사용하여 상품명 생성
                String productName = fakerInstance.commerce().productName();
                int price = random.nextInt(1000, 100000);
                int quantity = random.nextInt(1, 10);

                OrderItem item = OrderItem.of(productId, productName, price, quantity);
                items.add(item);
            }

            Order order = Order.of(userId, items, null, null);
            if (status == OrderStatus.COMPLETED) {
                order.complete();
            } else if (status == OrderStatus.CANCELED) {
                order.cancel();
            }

            orders.add(order);
            orderCount++;

            if (orders.size() >= config.getBatchSize()) {
                orderJpaRepository.saveAll(orders);
                orders.clear();
                log.debug("주문 {}개 저장 완료", config.getBatchSize());
            }
        }

        if (!orders.isEmpty()) {
            orderJpaRepository.saveAll(orders);
        }

        long endTime = System.currentTimeMillis();
        log.info("주문 데이터 생성 완료: {}개 (소요 시간: {}ms)", orderCount, endTime - startTime);
    }

    /**
     * 랜덤한 주문 상태를 반환합니다.
     *
     * @return 주문 상태
     */
    private OrderStatus getRandomOrderStatus() {
        double rand = random.nextDouble();
        if (rand < 0.7) {
            return OrderStatus.COMPLETED; // 70%
        } else if (rand < 0.9) {
            return OrderStatus.PENDING; // 20%
        } else {
            return OrderStatus.CANCELED; // 10%
        }
    }
}

