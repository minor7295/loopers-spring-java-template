package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 비동기 처리를 위한 ExecutorService 설정.
 * <p>
 * CompletableFuture를 사용하는 비동기 작업에 사용됩니다.
 * </p>
 */
@Configuration
public class AsyncConfig {

    /**
     * 데이터베이스 조회 작업을 위한 ExecutorService를 생성합니다.
     * <p>
     * 고정 크기 스레드 풀을 사용하여 동시에 실행 가능한 작업 수를 제한합니다.
     * </p>
     *
     * @return ExecutorService 인스턴스
     */
    @Bean
    public ExecutorService executorService() {
        return Executors.newFixedThreadPool(
            10, // 스레드 풀 크기
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "async-db-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );
    }
}

