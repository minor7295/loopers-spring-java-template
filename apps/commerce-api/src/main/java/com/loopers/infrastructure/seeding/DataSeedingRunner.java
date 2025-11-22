package com.loopers.infrastructure.seeding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 시작 시 데이터 시딩을 수행하는 CommandLineRunner.
 * <p>
 * data.seeding.enabled=true로 설정하면 애플리케이션 시작 시 자동으로 데이터를 생성합니다.
 * </p>
 * <p>
 * 주의: 이 클래스는 {@link DataSeedingApplication}에서만 사용됩니다.
 * {@link com.loopers.CommerceApiApplication}에서는 제외되어 실행되지 않습니다.
 * </p>
 *
 * @author Loopers
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "data.seeding.enabled", havingValue = "true", matchIfMissing = false)
public class DataSeedingRunner implements CommandLineRunner {

    private final DataSeedingService dataSeedingService;

    @Override
    public void run(String... args) {
        log.info("데이터 시딩이 활성화되어 있습니다. 데이터 생성을 시작합니다...");
        dataSeedingService.seedAll();
    }
}

