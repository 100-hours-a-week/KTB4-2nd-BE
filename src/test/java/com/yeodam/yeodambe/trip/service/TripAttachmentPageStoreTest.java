package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripAttachmentPageStoreTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock();
    private final TripAttachmentCacheProperties properties = new TripAttachmentCacheProperties();
    private final TripAttachmentPageStore store = new TripAttachmentPageStore(redis, new ObjectMapper(), properties);
    private final AttachmentPageKey query = new AttachmentPageKey(7L, 3L, null);

    @BeforeEach
    void prepare() {
        properties.setPageSchemaVersion(1);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void 데이터가_있는_페이지는_설정_TTL을_사용하고_다른_페이지나_시퀀스에서_조회할_수_없다() {
        AttachmentPageData page = new AttachmentPageData(List.of(new AttachmentPageData.Item(
                10L, LocalDateTime.of(2026, 11, 4, 12, 0), "preview")), false, null, 9L);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        store.save(query, page);

        verify(values).set(key.capture(), payload.capture(), eq(Duration.ofSeconds(60)));
        when(values.get(key.getValue())).thenReturn(payload.getValue());
        assertThat(store.find(query, 9L)).contains(page);
        assertThat(store.find(query, 10L)).isEmpty();
        assertThat(store.find(new AttachmentPageKey(7L, 4L, null), 9L)).isEmpty();
    }

    @Test
    void 정상적인_빈_첫_페이지만_짧게_캐시한다() {
        AttachmentPageData empty = new AttachmentPageData(List.of(), false, null, 9L);

        store.save(query, empty);

        verify(values).set(anyString(), anyString(), eq(Duration.ofSeconds(5)));
    }

    @Test
    void 마지막_빈_페이지는_캐시하지_않는다() {
        store.save(new AttachmentPageKey(7L, 3L, "cursor"),
                new AttachmentPageData(List.of(), false, null, 9L));

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 다른_시퀀스의_페이로드를_사용하지_않는다() {
        when(values.get(anyString())).thenReturn("""
                {"schemaVersion":1,"key":{"tripId":7,"tripPlaceId":3,"cursor":null},
                 "page":{"items":[],"hasNext":false,"nextCursor":null,"sequence":8}}
                """);

        assertThat(store.find(query, 9L)).isEmpty();
    }

    @Test
    void null_페이로드는_캐시_미스로_처리한다() {
        when(values.get(anyString())).thenReturn("null");

        assertThat(store.find(query, 9L)).isEmpty();
    }

    @Test
    void 조회_대상과_시퀀스가_같아도_다른_스키마의_payload는_사용하지_않는다() {
        when(values.get(anyString())).thenReturn("""
                {"schemaVersion":99,"key":{"tripId":7,"tripPlaceId":3,"cursor":null},
                 "page":{"items":[],"hasNext":false,"nextCursor":null,"sequence":9}}
                """);

        assertThat(store.find(query, 9L)).isEmpty();
    }
}
