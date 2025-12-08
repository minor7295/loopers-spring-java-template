package com.loopers.infrastructure.payment;

import java.time.Duration;

/**
 * 지연 제공자 인터페이스.
 * <p>
 * 테스트 가능성을 위해 Thread.sleep을 추상화합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
public interface DelayProvider {
    
    /**
     * 지정된 시간만큼 대기합니다.
     *
     * @param duration 대기 시간
     * @throws InterruptedException 인터럽트 발생 시
     */
    void delay(Duration duration) throws InterruptedException;
}
