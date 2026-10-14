package com.yeodam.yeodambe;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SharedRedisConfigurationTest {

    @Test
    void 같은_Redis의_독립_DB를_사용하고_컨텍스트_종료_후에도_다른_DB를_유지한다() {
        try (var first = new AnnotationConfigApplicationContext(TestcontainersConfiguration.class);
             var second = new AnnotationConfigApplicationContext(TestcontainersConfiguration.class)) {
            var firstDetails = first.getBean(DataRedisConnectionDetails.class).getStandalone();
            var secondDetails = second.getBean(DataRedisConnectionDetails.class).getStandalone();
            assertThat(firstDetails).isNotNull();
            assertThat(secondDetails).isNotNull();
            assertThat(firstDetails.getHost()).isEqualTo(secondDetails.getHost());
            assertThat(firstDetails.getPort()).isEqualTo(secondDetails.getPort());
            assertThat(firstDetails.getDatabase()).isNotEqualTo(secondDetails.getDatabase());

            var firstConnection = connection(firstDetails);
            var secondConnection = connection(secondDetails);
            try {
                var firstRedis = new StringRedisTemplate(firstConnection);
                var secondRedis = new StringRedisTemplate(secondConnection);
                firstRedis.opsForValue().set("isolation-probe", "first");
                assertThat(secondRedis.opsForValue().get("isolation-probe")).isNull();
                secondRedis.opsForValue().set("isolation-probe", "second");
                assertThat(firstRedis.opsForValue().get("isolation-probe")).isEqualTo("first");

                first.close();
                assertThat(secondRedis.opsForValue().get("isolation-probe")).isEqualTo("second");
                secondRedis.opsForValue().set("after-close", "available");
                assertThat(secondRedis.opsForValue().get("after-close")).isEqualTo("available");
            } finally {
                firstConnection.destroy();
                secondConnection.destroy();
            }
        }
    }

    private LettuceConnectionFactory connection(DataRedisConnectionDetails.Standalone details) {
        var configuration = new RedisStandaloneConfiguration(details.getHost(), details.getPort());
        configuration.setDatabase(details.getDatabase());
        var factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
