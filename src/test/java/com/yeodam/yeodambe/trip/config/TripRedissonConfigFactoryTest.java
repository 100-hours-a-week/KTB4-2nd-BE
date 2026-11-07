package com.yeodam.yeodambe.trip.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.config.BaseConfig;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.redisson.config.SslVerificationMode;
import org.redisson.config.SubscriptionMode;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripRedissonConfigFactoryTest {

    @Test
    void Sentinel과_데이터_계정을_분리하고_TLS를_엄격히_검증한다() {
        RedisSentinelConfiguration sentinel = sentinelConfiguration();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3)).useSsl().build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinel, client);
        DataRedisProperties properties = properties();

        Config config = TripRedissonConfigFactory.createConnectionConfig(factory, properties);
        SentinelServersConfig server = config.useSentinelServers();

        assertThat(server.getMasterName()).isEqualTo("yeodam-v3-app-redis");
        assertThat(server.getSentinelAddresses()).containsExactlyInAnyOrder(
                "rediss://sentinel-0.redis-sentinel.redis.svc.cluster.local:26379",
                "rediss://sentinel-1.redis-sentinel.redis.svc.cluster.local:26379",
                "rediss://sentinel-2.redis-sentinel.redis.svc.cluster.local:26379");
        assertThat(server.getSentinelUsername()).isEqualTo("sentinel-client");
        assertThat(server.getSentinelPassword()).isEqualTo("sentinel-test-password");
        assertThat(config.getUsername()).isEqualTo("app");
        assertThat(config.getPassword()).isEqualTo("data-test-password");
        assertThat(config.getSslVerificationMode()).isEqualTo(SslVerificationMode.STRICT);
        assertThat(server.getDatabase()).isEqualTo(2);
        assertThat(server.getTimeout()).isEqualTo(3000);
        assertThat(server.getConnectTimeout()).isEqualTo(1500);
        assertThat(server.getReadMode()).isEqualTo(ReadMode.MASTER);
        assertThat(server.getSubscriptionMode()).isEqualTo(SubscriptionMode.MASTER);
        assertThat(server.isSentinelsDiscovery()).isTrue();
        assertThat(server.isCheckSentinelsList()).isTrue();
    }

    @Test
    void Sentinel이_없으면_기존_단일_연결과_인증을_유지한다() {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("localhost", 6380);
        standalone.setDatabase(3);
        standalone.setUsername("local-app");
        standalone.setPassword(RedisPassword.of("local-test-password"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone);

        Config config = TripRedissonConfigFactory.createConnectionConfig(factory, properties());
        SingleServerConfig server = config.useSingleServer();

        assertThat(server.getAddress()).isEqualTo("redis://localhost:6380");
        assertThat(server.getDatabase()).isEqualTo(3);
        assertThat(config.getUsername()).isEqualTo("local-app");
        assertThat(config.getPassword()).isEqualTo("local-test-password");
        assertThat(server.getConnectTimeout()).isEqualTo(1500);
    }

    @Test
    void Sentinel_감시_이름이_없으면_단일_연결로_대체하지_않는다() {
        RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration().sentinel("localhost", 26379);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinel);

        assertThatThrownBy(() -> TripRedissonConfigFactory.createConnectionConfig(factory, properties()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void Sentinel_주소가_없으면_연결_설정을_거절한다() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisSentinelConfiguration().master("yeodam-v3-app-redis"));

        assertThatThrownBy(() -> TripRedissonConfigFactory.createConnectionConfig(factory, properties()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 지원하지_않는_클러스터_설정은_단일_연결로_대체하지_않는다() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisClusterConfiguration());

        assertThatThrownBy(() -> TripRedissonConfigFactory.createConnectionConfig(factory, properties()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TLS_검증이_꺼진_Lettuce_설정이어도_Redisson은_엄격히_검증한다() {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .useSsl().disablePeerVerification().build();
        Config config = TripRedissonConfigFactory.createConnectionConfig(
                new LettuceConnectionFactory(sentinelConfiguration(), client), properties());

        assertThat(config.getSslVerificationMode()).isEqualTo(SslVerificationMode.STRICT);
        assertThat(config.useSentinelServers().getSentinelAddresses()).allMatch(value -> value.startsWith("rediss://"));
    }

    @Test
    void Sentinel_연결에서도_기존_풀과_구독_정책을_적용한다() {
        Config config = TripRedissonConfigFactory.createConnectionConfig(
                new LettuceConnectionFactory(sentinelConfiguration()), properties());
        SingleServerConfig settings = new Config().useSingleServer()
                .setSubscriptionTimeout(1500).setRetryAttempts(1)
                .setConnectionMinimumIdleSize(2).setConnectionPoolSize(6)
                .setSubscriptionConnectionMinimumIdleSize(2).setSubscriptionConnectionPoolSize(5);

        TripRedissonConfigFactory.applyServerSettings(config, settings);

        SentinelServersConfig server = config.useSentinelServers();
        assertThat(server.getMasterConnectionMinimumIdleSize()).isEqualTo(2);
        assertThat(server.getMasterConnectionPoolSize()).isEqualTo(6);
        assertThat(server.getSlaveConnectionMinimumIdleSize()).isEqualTo(2);
        assertThat(server.getSlaveConnectionPoolSize()).isEqualTo(6);
        assertThat(server.getSubscriptionConnectionMinimumIdleSize()).isEqualTo(2);
        assertThat(server.getSubscriptionConnectionPoolSize()).isEqualTo(5);
        assertThat(server.getSubscriptionTimeout()).isEqualTo(1500);
        assertThat(server.getRetryAttempts()).isEqualTo(1);
    }

    @Test
    void 밀리초로_표현할_수_없는_연결_제한_시간은_설정_오류로_거절한다() {
        DataRedisProperties properties = properties();
        properties.setConnectTimeout(Duration.ofSeconds(Long.MAX_VALUE));

        assertThatThrownBy(() -> TripRedissonConfigFactory.createConnectionConfig(
                new LettuceConnectionFactory(sentinelConfiguration()), properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 두_연결_방식에서_기존_서버_설정을_보존하고_TLS를_엄격히_검증한다(boolean sentinel) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.trip.edit-lock.client.server.timeout", "1700")
                .withProperty("app.trip.edit-lock.client.server.connect-timeout", "1800")
                .withProperty("app.trip.edit-lock.client.server.idle-connection-timeout", "6000")
                .withProperty("app.trip.edit-lock.client.server.ping-connection-interval", "12000")
                .withProperty("app.trip.edit-lock.client.server.subscriptions-per-connection", "3")
                .withProperty("app.trip.edit-lock.client.server.connection-minimum-idle-size", "2")
                .withProperty("app.trip.edit-lock.client.server.connection-pool-size", "6")
                .withProperty("app.trip.edit-lock.client.ssl-verification-mode", "NONE")
                .withProperty("app.trip.edit-lock.client.server.ssl-verification-mode", "NONE");
        LettuceConnectionFactory factory = sentinel
                ? new LettuceConnectionFactory(sentinelConfiguration())
                : new LettuceConnectionFactory(new RedisStandaloneConfiguration());
        Config config = new TripEditLockConfiguration().createConfig(
                factory, properties(),
                new TripEditLockProperties(true, "yeodam:v3", Duration.ofSeconds(2), Duration.ofSeconds(30)),
                environment);
        BaseConfig<?> server = sentinel ? config.useSentinelServers() : config.useSingleServer();

        assertThat(server.getTimeout()).isEqualTo(1700);
        assertThat(server.getConnectTimeout()).isEqualTo(1800);
        assertThat(server.getIdleConnectionTimeout()).isEqualTo(6000);
        assertThat(server.getPingConnectionInterval()).isEqualTo(12000);
        assertThat(server.getSubscriptionsPerConnection()).isEqualTo(3);
        assertThat(config.getSslVerificationMode()).isEqualTo(SslVerificationMode.STRICT);
        assertThat(server.getSslVerificationMode()).isEqualTo(SslVerificationMode.STRICT);
        if (sentinel) {
            assertThat(config.useSentinelServers().getMasterConnectionMinimumIdleSize()).isEqualTo(2);
            assertThat(config.useSentinelServers().getMasterConnectionPoolSize()).isEqualTo(6);
        } else {
            assertThat(config.useSingleServer().getConnectionMinimumIdleSize()).isEqualTo(2);
            assertThat(config.useSingleServer().getConnectionPoolSize()).isEqualTo(6);
        }
    }

    private RedisSentinelConfiguration sentinelConfiguration() {
        RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration().master("yeodam-v3-app-redis");
        for (int index = 0; index < 3; index++) {
            sentinel.sentinel("sentinel-" + index + ".redis-sentinel.redis.svc.cluster.local", 26379);
        }
        sentinel.setDatabase(2);
        sentinel.setUsername("app");
        sentinel.setPassword(RedisPassword.of("data-test-password"));
        sentinel.setSentinelUsername("sentinel-client");
        sentinel.setSentinelPassword(RedisPassword.of("sentinel-test-password"));
        return sentinel;
    }

    private DataRedisProperties properties() {
        DataRedisProperties properties = new DataRedisProperties();
        properties.setConnectTimeout(Duration.ofMillis(1500));
        return properties;
    }
}
