package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.client.TripAttachmentRedisLockClient;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripRedissonClientConfigurationTest {

    @Test
    void 두_Redisson_빈이_있어도_사진_잠금_클라이언트를_주입한다() throws InterruptedException {
        RedissonClient photos = mock(RedissonClient.class);
        RedissonClient edits = mock(RedissonClient.class);
        RLock photoLock = mock(RLock.class);
        TripAttachmentCacheProperties properties = new TripAttachmentCacheProperties();
        properties.setAccessSchemaVersion(1);
        properties.setResponseSchemaVersion(1);
        properties.setPageSchemaVersion(1);
        when(photos.getLock("photo-test-lock")).thenReturn(photoLock);
        when(photoLock.tryLock(0L, properties.getReadLockLease().toNanos(), TimeUnit.NANOSECONDS)).thenReturn(true);
        new ApplicationContextRunner()
                .withBean("photoListRedissonClient", RedissonClient.class, () -> photos)
                .withBean("tripEditRedissonClient", RedissonClient.class, () -> edits)
                .withBean(TripAttachmentCacheProperties.class, () -> properties)
                .withUserConfiguration(TripAttachmentRedisLockClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TripAttachmentRedisLockClient.class)
                            .tryAcquire(2L, "photo-test-lock", 0L)).contains(photoLock);
                });
    }
}
