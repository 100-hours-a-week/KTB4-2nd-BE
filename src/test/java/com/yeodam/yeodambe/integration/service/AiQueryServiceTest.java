package com.yeodam.yeodambe.integration.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
import com.yeodam.yeodambe.integration.client.AiQueryClient;
import com.yeodam.yeodambe.integration.service.request.AiQueryParseRequest;
import com.yeodam.yeodambe.integration.service.request.AiQuerySearchRequest;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse;
import com.yeodam.yeodambe.integration.service.response.AiQuerySearchResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiQueryServiceTest {
    private final AiQueryClient client = mock(AiQueryClient.class);
    private final AiQueryService service = new AiQueryService(client, Clock.fixed(
            Instant.parse("2026-10-11T15:30:00Z"), ZoneOffset.UTC));
    private final AiQuerySearchRequest request = new AiQuerySearchRequest(
            "제주 사진", AiQueryParseResponse.Intent.QUESTION, null,
            List.of(new AiQuerySearchRequest.Candidate(1L, "analyze", null, null, null)), 30
    );

    @Test
    void 파싱_응답_계약_위반을_거부하고_필드와_원인을_기록한다() {
        List<AiQueryParseResponse> invalid = Arrays.asList(
                null,
                new AiQueryParseResponse(null, null, null, null, null, true),
                new AiQueryParseResponse(AiQueryParseResponse.Intent.SEARCH,
                        LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 7), null, null, true),
                new AiQueryParseResponse(AiQueryParseResponse.Intent.SEARCH,
                        null, LocalDate.MAX, null, null, true),
                new AiQueryParseResponse(AiQueryParseResponse.Intent.SEARCH,
                        null, null, Arrays.asList((String) null), null, true),
                new AiQueryParseResponse(AiQueryParseResponse.Intent.SEARCH,
                        null, null, List.of(" "), null, true)
        );
        List<String> fields = List.of("response", "intent", "date_from,date_to",
                "date_to", "region_names", "region_names");
        List<String> reasons = List.of("missing", "missing", "invalid_range",
                "unsupported_max_date", "null_or_blank_element", "null_or_blank_element");
        Logger logger = (Logger) LoggerFactory.getLogger(AiQueryService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (int index = 0; index < invalid.size(); index++) {
                logs.list.clear();
                when(client.parse(any())).thenReturn(invalid.get(index));
                assertThatThrownBy(() -> service.parse("제주 사진"))
                        .isInstanceOf(AiQueryUnavailableException.class);
                assertThat(logs.list).hasSize(1);
                assertThat(logs.list.getFirst().getKeyValuePairs())
                        .extracting(pair -> pair.key, pair -> pair.value)
                        .contains(tuple("stage", "parse"), tuple("failure", "response_contract"),
                                tuple("field", fields.get(index)), tuple("reason", reasons.get(index)));
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void 사용자_검색_시각의_KST_날짜를_전달한다() {
        when(client.parse(any())).thenReturn(new AiQueryParseResponse(
                AiQueryParseResponse.Intent.SEARCH, null, null, null, null, true));
        service.parse("제주 사진");
        var request = ArgumentCaptor.forClass(
                AiQueryParseRequest.class);
        verify(client).parse(request.capture());
        assertThat(request.getValue().referenceDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(request.getValue().query()).isEqualTo("제주 사진");
    }

    @Test
    void parsed_누락을_거부하고_분해_실패는_허용한다() {
        when(client.parse(any())).thenReturn(new AiQueryParseResponse(
                AiQueryParseResponse.Intent.SEARCH, null, null, null, null, null));
        assertThatThrownBy(() -> service.parse("제주 사진"))
                .isInstanceOf(AiQueryUnavailableException.class);
        when(client.parse(any())).thenReturn(new AiQueryParseResponse(
                AiQueryParseResponse.Intent.SEARCH, null, null, null, "제주 사진", false));
        assertThat(service.parse("제주 사진").parsed()).isFalse();
    }

    @Test
    void 후보_ID와_분석용_키가_유효하지_않으면_AI에_전달하지_않는다() {
        for (AiQuerySearchRequest.Candidate candidate : List.of(
                new AiQuerySearchRequest.Candidate(0L, "analyze", null, null, null),
                new AiQuerySearchRequest.Candidate(1L, " ", null, null, null))) {
            var invalid = new AiQuerySearchRequest("제주 사진", AiQueryParseResponse.Intent.SEARCH,
                    null, List.of(candidate), 30);
            assertThatThrownBy(() -> service.search(invalid))
                    .isInstanceOf(AiQueryUnavailableException.class);
        }
        verify(client, never()).search(any());
    }

    @Test
    void 검색_응답_계약_위반을_거부한다() {
        List<AiQuerySearchResponse> invalid = Arrays.asList(
                null, new AiQuerySearchResponse(null, null, null),
                new AiQuerySearchResponse(Arrays.asList((AiQuerySearchResponse.Attachment) null), null, null)
        );
        for (AiQuerySearchResponse response : invalid) {
            when(client.search(request)).thenReturn(response);
            assertThatThrownBy(() -> service.search(request))
                    .isInstanceOf(AiQueryUnavailableException.class);
        }
        for (Long id : Arrays.asList(null, 0L, -1L)) {
            when(client.search(request)).thenReturn(new AiQuerySearchResponse(
                    List.of(new AiQuerySearchResponse.Attachment(id, 0.5)), null, null));
            assertThatThrownBy(() -> service.search(request))
                    .isInstanceOf(AiQueryUnavailableException.class);
        }
        for (Double score : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            when(client.search(request)).thenReturn(new AiQuerySearchResponse(
                    List.of(new AiQuerySearchResponse.Attachment(1L, score)), null, null));
            assertThatThrownBy(() -> service.search(request))
                    .isInstanceOf(AiQueryUnavailableException.class);
        }
    }

    @Test
    void 빈_검색_결과와_답변_실패와_유한한_음수_점수를_허용한다() {
        AiQueryParseResponse parsed = new AiQueryParseResponse(
                AiQueryParseResponse.Intent.SEARCH, null, null, null, null, true);
        when(client.parse(any())).thenReturn(parsed);
        assertThat(service.parse("제주 사진")).isEqualTo(parsed);
        AiQuerySearchResponse failedAnswer = new AiQuerySearchResponse(
                List.of(new AiQuerySearchResponse.Attachment(1L, -0.2)), null, "ANSWER_FAILED");
        when(client.search(request)).thenReturn(failedAnswer);
        assertThat(service.search(request)).isEqualTo(failedAnswer);
        when(client.search(request)).thenReturn(new AiQuerySearchResponse(List.of(), null, null));
        assertThat(service.search(request).attachments()).isEmpty();
    }
}
