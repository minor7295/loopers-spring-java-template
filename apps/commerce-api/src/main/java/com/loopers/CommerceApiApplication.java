package com.loopers;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

@ConfigurationPropertiesScan
@SpringBootApplication
@ComponentScan(
    basePackages = "com.loopers",
    excludeFilters = {
        // 데이터 시딩 관련 클래스들은 독립 실행 전용이므로 제외
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {
                com.loopers.infrastructure.seeding.DataSeedingApplication.class,
                com.loopers.infrastructure.seeding.DataSeedingRunner.class,
                com.loopers.application.scheduler.LikeCountSyncScheduler.class
            }
        )
    }
)
@EnableScheduling
public class CommerceApiApplication {

    @PostConstruct
    public void started() {
        // set timezone
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(CommerceApiApplication.class, args);
    }
}
