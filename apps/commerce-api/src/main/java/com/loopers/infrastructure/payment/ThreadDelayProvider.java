package com.loopers.infrastructure.payment;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Thread.sleep을 사용하는 DelayProvider 구현체.
 *
 * @author Loopers
 * @version 1.0
 */
@Component
public class ThreadDelayProvider implements DelayProvider {
    
    @Override
    public void delay(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }
}
