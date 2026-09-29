package com.yeodam.yeodambe.trip.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.trip.client.KakaoLocalClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TripPlaceNameServiceTest {
    private final KakaoLocalClient client = mock(KakaoLocalClient.class);
    private final InitialUploadExecutionRegistry executions = mock(InitialUploadExecutionRegistry.class);
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
        MDC.clear();
    }

    @Test
    void AI_장소_좌표와_ID가_올바르지_않으면_호출하지_않는다() {
        TripPlaceNameService service = service(5);
        when(executions.isCurrent(7L, "run")).thenReturn(true);

        for (String result : new String[]{
                "{\"places\":[{\"place_id\":\"p1\",\"longitude\":126.94}]}",
                "{\"places\":[{\"place_id\":\"p1\",\"latitude\":\"x\",\"longitude\":126.94}]}",
                "{\"places\":[{\"place_id\":\"p1\",\"latitude\":91,\"longitude\":126.94}]}",
                "{\"places\":[{\"place_id\":\"p1\",\"latitude\":33.45,\"longitude\":181}]}",
                "{\"places\":[{\"place_id\":\" \",\"latitude\":33.45,\"longitude\":126.94}]}",
                "{\"places\":[{\"place_id\":\"p1\",\"latitude\":33.45,\"longitude\":126.94},"
                        + "{\"place_id\":\"p1\",\"latitude\":34,\"longitude\":127}]}"
        }) {
            assertThatThrownBy(() -> service.resolve(7L, "run", json.readTree(result)))
                    .isInstanceOf(IllegalStateException.class);
        }

        verifyNoInteractions(client);
    }

    @Test
    void 동시_호출_설정은_한개에서_다섯개까지만_허용한다() {
        assertThatThrownBy(() -> service(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 건물명_우선과_지역명_및_임시이름_대체를_적용한다() {
        TripPlaceNameService service = service(5);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        when(client.lookup(new BigDecimal("33.45"), new BigDecimal("126.94")))
                .thenReturn(success("성산일출봉", "서귀포시"));
        when(client.lookup(new BigDecimal("35.18"), new BigDecimal("129.07")))
                .thenReturn(success(null, "부산광역시"));
        when(client.lookup(new BigDecimal("37.56"), new BigDecimal("126.97")))
                .thenReturn(failure(KakaoLocalClient.Failure.OTHER));

        Map<String, String> names = service.resolve(7L, "run", json.readTree("""
                {"places":[
                  {"place_id":"p1","latitude":33.45,"longitude":126.94},
                  {"place_id":"p2","latitude":35.18,"longitude":129.07},
                  {"place_id":"p3","latitude":37.56,"longitude":126.97}
                ]}
                """));

        assertThat(names).containsExactly(
                Map.entry("p1", "성산일출봉"),
                Map.entry("p2", "부산광역시"),
                Map.entry("p3", "장소 3"));
    }

    @Test
    void 중복_이름에_순번을_붙이고_접미사를_포함해_50자로_자른다() {
        TripPlaceNameService service = service(5);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        String longName = "😀" + "가".repeat(49);
        when(client.lookup(any(), any())).thenReturn(success(longName, null));

        Map<String, String> names = service.resolve(7L, "run", json.readTree("""
                {"places":[
                  {"place_id":"p1","latitude":33.45,"longitude":126.94},
                  {"place_id":"p2","latitude":35.18,"longitude":129.07}
                ]}
                """));

        assertThat(names.get("p1").codePointCount(0, names.get("p1").length())).isEqualTo(50);
        assertThat(names.get("p1")).endsWith(" 1").startsWith("😀");
        assertThat(names.get("p2")).endsWith(" 2").startsWith("😀");
    }

    @Test
    void 재시도_가능한_실패만_한번_더_호출한다() {
        TripPlaceNameService service = service(5);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        when(client.lookup(any(), any()))
                .thenReturn(failure(KakaoLocalClient.Failure.RETRYABLE))
                .thenReturn(success("재시도 성공", "서울특별시"));

        Map<String, String> names = service.resolve(7L, "run", onePlace());

        assertThat(names.get("p1")).isEqualTo("재시도 성공");
        verify(client, times(2)).lookup(any(), any());
    }

    @Test
    void 재시도도_실패하면_그_장소만_대체하고_다음_장소는_유지한다() {
        TripPlaceNameService service = service(1);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        when(client.lookup(any(), any()))
                .thenReturn(failure(KakaoLocalClient.Failure.RETRYABLE))
                .thenReturn(failure(KakaoLocalClient.Failure.RETRYABLE))
                .thenReturn(success("다음 장소", "서울특별시"));

        Map<String, String> names = service.resolve(7L, "run", json.readTree("""
                {"places":[
                  {"place_id":"p1","latitude":33.45,"longitude":126.94},
                  {"place_id":"p2","latitude":37.56,"longitude":126.97}
                ]}
                """));

        assertThat(names).containsExactly(
                Map.entry("p1", "장소 1"), Map.entry("p2", "다음 장소"));
        verify(client, times(3)).lookup(any(), any());
    }

    @Test
    void 인증과_일반실패는_재시도하지_않고_각_장소만_대체한다() {
        TripPlaceNameService service = service(5);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        when(client.lookup(any(), any()))
                .thenReturn(failure(KakaoLocalClient.Failure.AUTH))
                .thenReturn(failure(KakaoLocalClient.Failure.OTHER));

        Map<String, String> names = service.resolve(7L, "run", json.readTree("""
                {"places":[
                  {"place_id":"p1","latitude":33.45,"longitude":126.94},
                  {"place_id":"p2","latitude":35.18,"longitude":129.07}
                ]}
                """));

        assertThat(names).containsExactly(Map.entry("p1", "장소 1"), Map.entry("p2", "장소 2"));
        verify(client, times(2)).lookup(any(), any());
    }

    @Test
    void 요청_ID를_장소_조회_작업과_실패_로그에_전달한다() {
        TripPlaceNameService service = service(1);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        AtomicReference<String> workerRequestId = new AtomicReference<>();
        when(client.lookup(any(), any())).thenAnswer(call -> {
            workerRequestId.set(MDC.get("request_id"));
            return failure(KakaoLocalClient.Failure.AUTH);
        });
        Logger logger = (Logger) LoggerFactory.getLogger(TripPlaceNameService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            MDC.put("request_id", "request-123");
            assertThat(service.resolve(7L, "run", onePlace())).containsEntry("p1", "장소 1");

            assertThat(workerRequestId.get()).isEqualTo("request-123");
            ILoggingEvent event = appender.list.stream()
                    .filter(log -> log.getFormattedMessage().startsWith("Kakao 장소명 인증 실패"))
                    .findFirst().orElseThrow();
            assertThat(event.getMDCPropertyMap()).containsEntry("request_id", "request-123");
            assertThat(MDC.get("request_id")).isEqualTo("request-123");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 요청_ID가_없으면_이전_작업의_MDC를_다음_조회에_남기지_않는다() {
        TripPlaceNameService service = service(1);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondRequestId = new AtomicReference<>();
        when(client.lookup(any(), any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) MDC.put("request_id", "stale-request");
            else secondRequestId.set(MDC.get("request_id"));
            return success("이름", "지역");
        });

        MDC.clear();
        service.resolve(7L, "run", places(2));

        assertThat(calls).hasValue(2);
        assertThat(secondRequestId.get()).isNull();
    }

    @Test
    void scale만_다른_동일_좌표는_한번만_조회한다() {
        TripPlaceNameService service = service(5);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        when(client.lookup(any(), any())).thenReturn(success("같은 곳", "제주시"));

        Map<String, String> names = service.resolve(7L, "run", json.readTree("""
                {"places":[
                  {"place_id":"p1","latitude":33.450,"longitude":126.940},
                  {"place_id":"p2","latitude":33.45,"longitude":126.94}
                ]}
                """));

        assertThat(names).containsExactly(Map.entry("p1", "같은 곳 1"), Map.entry("p2", "같은 곳 2"));
        verify(client, times(1)).lookup(any(), any());
    }

    @Test
    void 동시에_최대_다섯_장소만_조회한다() {
        TripPlaceNameService service = service(5);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(client.lookup(any(), any())).thenAnswer(call -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            Thread.sleep(30);
            active.decrementAndGet();
            return success("이름", "지역");
        });

        service.resolve(7L, "run", places(11));

        assertThat(maximum).hasValueLessThanOrEqualTo(5);
        assertThat(maximum).hasValueGreaterThan(1);
    }

    @Test
    void 다음_묶음_전에_취소되면_추가_조회하지_않는다() {
        TripPlaceNameService service = service(5);
        when(executions.isCurrent(7L, "run")).thenReturn(true, false);
        when(client.lookup(any(), any())).thenReturn(success("이름", "지역"));

        assertThatThrownBy(() -> service.resolve(7L, "run", places(6)))
                .isInstanceOf(IllegalStateException.class);
        verify(client, times(5)).lookup(any(), any());
    }

    @Test
    void 대기중_인터럽트되면_인터럽트_상태를_복구하고_중단한다() throws Exception {
        TripPlaceNameService service = service(5);
        when(executions.isCurrent(7L, "run")).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.lookup(any(), any())).thenAnswer(call -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return success("이름", "지역");
        });
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> service.resolve(7L, "run", onePlace()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        release.countDown();
    }

    private TripPlaceNameService service(int concurrency) {
        return new TripPlaceNameService(client, executions, concurrency);
    }

    private tools.jackson.databind.JsonNode onePlace() {
        return json.readTree("{\"places\":[{\"place_id\":\"p1\",\"latitude\":33.45,\"longitude\":126.94}]}");
    }

    private tools.jackson.databind.JsonNode places(int count) {
        StringBuilder body = new StringBuilder("{\"places\":[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) body.append(',');
            body.append("{\"place_id\":\"p").append(i)
                    .append("\",\"latitude\":33.").append(i)
                    .append(",\"longitude\":126.").append(i).append('}');
        }
        return json.readTree(body.append("]}").toString());
    }

    private KakaoLocalClient.LookupResult success(String building, String region) {
        return new KakaoLocalClient.LookupResult(building, region, KakaoLocalClient.Failure.NONE);
    }

    private KakaoLocalClient.LookupResult failure(KakaoLocalClient.Failure failure) {
        return new KakaoLocalClient.LookupResult(null, null, failure);
    }
}
