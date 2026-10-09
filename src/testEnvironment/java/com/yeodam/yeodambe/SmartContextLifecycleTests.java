package com.yeodam.yeodambe;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(SmartContextProbe.class)
@TestPropertySource(properties = "comparison.context=first")
class FirstSmartContextTest {
    @Test
    void 이전_그룹의_컨텍스트를_종료한_뒤_다음_그룹을_실행한다() {
        assertThat(SmartContextProbe.ACTIVE.get()).isEqualTo(1);
    }
}

@SpringJUnitConfig(SmartContextProbe.class)
@TestPropertySource(properties = "comparison.context=second")
class SecondSmartContextTest {
    @Test
    void 이전_그룹의_컨텍스트를_종료한_뒤_다음_그룹을_실행한다() {
        assertThat(SmartContextProbe.ACTIVE.get()).isEqualTo(1);
    }
}

@Configuration(proxyBeanMethods = false)
class SmartContextProbe {
    static final AtomicInteger ACTIVE = new AtomicInteger();

    @Bean(destroyMethod = "close")
    AutoCloseable countedResource() {
        ACTIVE.incrementAndGet();
        return () -> ACTIVE.decrementAndGet();
    }
}
