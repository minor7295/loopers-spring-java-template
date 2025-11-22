package com.loopers.infrastructure.seeding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 데이터 시딩 설정을 관리하는 Configuration 클래스.
 * <p>
 * 대량 데이터 생성을 위한 설정값을 관리합니다.
 * </p>
 *
 * @author Loopers
 */
@Configuration
@ConfigurationProperties(prefix = "data.seeding")
public class DataSeedingConfig {

    /**
     * 데이터 시딩 활성화 여부
     */
    private boolean enabled = false;

    /**
     * 생성할 사용자 수
     */
    private int userCount = 10000;

    /**
     * 생성할 브랜드 수
     */
    private int brandCount = 100;

    /**
     * 생성할 상품 수
     */
    private int productCount = 100000;

    /**
     * 생성할 좋아요 수 (사용자당 평균 좋아요 수)
     */
    private int likesPerUser = 50;

    /**
     * 생성할 주문 수
     */
    private int orderCount = 50000;

    /**
     * 배치 크기 (한 번에 저장할 엔티티 수)
     */
    private int batchSize = 1000;

    /**
     * Faker 로케일 (예: "ko", "en")
     */
    private String fakerLocale = "ko";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getUserCount() {
        return userCount;
    }

    public void setUserCount(int userCount) {
        this.userCount = userCount;
    }

    public int getBrandCount() {
        return brandCount;
    }

    public void setBrandCount(int brandCount) {
        this.brandCount = brandCount;
    }

    public int getProductCount() {
        return productCount;
    }

    public void setProductCount(int productCount) {
        this.productCount = productCount;
    }

    public int getLikesPerUser() {
        return likesPerUser;
    }

    public void setLikesPerUser(int likesPerUser) {
        this.likesPerUser = likesPerUser;
    }

    public int getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(int orderCount) {
        this.orderCount = orderCount;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String getFakerLocale() {
        return fakerLocale;
    }

    public void setFakerLocale(String fakerLocale) {
        this.fakerLocale = fakerLocale;
    }
}

