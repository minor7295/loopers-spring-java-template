package com.loopers.infrastructure.seeding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * 데이터 시딩 전용 실행 애플리케이션.
 * <p>
 * 웹 서버 없이 데이터 시딩만 실행하는 독립 실행 가능한 애플리케이션입니다.
 * </p>
 * <p>
 * 사용 방법:
 * <ul>
 *   <li>Gradle: {@code ./gradlew :apps:commerce-api:runSeeding}</li>
 *   <li>JAR: {@code java -jar commerce-api.jar --spring.main.web-application-type=none}</li>
 *   <li>직접 실행: {@code java -cp ... com.loopers.infrastructure.seeding.DataSeedingApplication}</li>
 * </ul>
 * </p>
 *
 * @author Loopers
 */
@Slf4j
@SpringBootApplication(
    scanBasePackages = "com.loopers",
    exclude = {
        // CommerceApiApplication과 충돌 방지를 위해 제외
        // 이 클래스는 독립 실행 전용이므로 다른 애플리케이션에서 스캔되지 않도록 함
    }
)
@ConfigurationPropertiesScan
@ComponentScan(basePackages = "com.loopers")
public class DataSeedingApplication implements CommandLineRunner {

    private final DataSeedingService dataSeedingService;

    public DataSeedingApplication(DataSeedingService dataSeedingService) {
        this.dataSeedingService = dataSeedingService;
    }

    public static void main(String[] args) {
        // 웹 서버 없이 실행
        System.setProperty("spring.main.web-application-type", "none");
        
        SpringApplication app = new SpringApplication(DataSeedingApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.run(args);
    }

    @Override
    public void run(String... args) {
        log.info("=== 데이터 시딩 스크립트 시작 ===");
        log.info("설정된 데이터 양:");
        log.info("  - 사용자: {}명", dataSeedingService.getConfig().getUserCount());
        log.info("  - 브랜드: {}개", dataSeedingService.getConfig().getBrandCount());
        log.info("  - 상품: {}개", dataSeedingService.getConfig().getProductCount());
        log.info("  - 좋아요: 사용자당 평균 {}개", dataSeedingService.getConfig().getLikesPerUser());
        log.info("  - 주문: {}개", dataSeedingService.getConfig().getOrderCount());
        log.info("  - 배치 크기: {}", dataSeedingService.getConfig().getBatchSize());
        log.info("  - Faker 로케일: {}", dataSeedingService.getConfig().getFakerLocale());
        log.info("");

        try {
            dataSeedingService.seedAll();
            log.info("=== 데이터 시딩 완료 ===");
            System.exit(0);
        } catch (Exception e) {
            log.error("데이터 시딩 중 오류 발생", e);
            System.exit(1);
        }
    }
}

