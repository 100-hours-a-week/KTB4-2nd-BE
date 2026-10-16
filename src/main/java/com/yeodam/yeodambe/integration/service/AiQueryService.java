package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.client.AiQueryClient;
import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
import com.yeodam.yeodambe.integration.service.request.AiQueryParseRequest;
import com.yeodam.yeodambe.integration.service.request.AiQuerySearchRequest;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse;
import com.yeodam.yeodambe.integration.service.response.AiQuerySearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiQueryService {
    private static final ZoneId SEARCH_ZONE = ZoneId.of("Asia/Seoul");
    private final AiQueryClient client;
    private final Clock clock;

    public AiQueryParseResponse parse(String query) {
        LocalDate referenceDate = LocalDate.now(clock.withZone(SEARCH_ZONE));
        AiQueryParseResponse response = client.parse(new AiQueryParseRequest(query, referenceDate));
        validateParseResponse(response);
        return response;
    }

    public AiQuerySearchResponse search(AiQuerySearchRequest request) {
        validateSearchCandidates(request);
        AiQuerySearchResponse response = client.search(request);
        validateSearchResponse(response);
        return response;
    }

    private void validateParseResponse(AiQueryParseResponse response) {
        if (response == null) {
            throw invalidParseResponse("response", "missing");
        }
        if (response.intent() == null) {
            throw invalidParseResponse("intent", "missing");
        }
        if (response.parsed() == null) {
            throw invalidParseResponse("parsed", "missing");
        }
        if (LocalDate.MAX.equals(response.dateTo())) {
            throw invalidParseResponse("date_to", "unsupported_max_date");
        }
        boolean invalidDateRange = response.dateFrom() != null && response.dateTo() != null
                && response.dateFrom().isAfter(response.dateTo());
        if (invalidDateRange) {
            throw invalidParseResponse("date_from,date_to", "invalid_range");
        }
        boolean invalidRegionNames = response.regionNames() != null && response.regionNames().stream()
                .anyMatch(region -> region == null || region.isBlank());
        if (invalidRegionNames) {
            throw invalidParseResponse("region_names", "null_or_blank_element");
        }
    }

    private AiQueryUnavailableException invalidParseResponse(String field, String reason) {
        log.atWarn()
                .addKeyValue("stage", "parse")
                .addKeyValue("failure", "response_contract")
                .addKeyValue("field", field)
                .addKeyValue("reason", reason)
                .log("AI 검색 응답 계약 검증에 실패했습니다.");
        return new AiQueryUnavailableException();
    }

    private void validateSearchCandidates(AiQuerySearchRequest request) {
        boolean invalid = request.candidates().stream().anyMatch(candidate ->
                candidate.tripAttachmentId() == null || candidate.tripAttachmentId() <= 0
                        || candidate.analyzeStorageKey() == null || candidate.analyzeStorageKey().isBlank());
        if (invalid) {
            log.atWarn()
                    .addKeyValue("stage", "search")
                    .addKeyValue("failure", "request_contract")
                    .addKeyValue("field", "candidates")
                    .log("AI 검색 후보의 ID 또는 분석용 저장 키가 올바르지 않습니다.");
            throw new AiQueryUnavailableException();
        }
    }

    private void validateSearchResponse(AiQuerySearchResponse response) {
        boolean invalid = response == null || response.attachments() == null
                || response.attachments().stream().anyMatch(attachment ->
                    attachment == null || attachment.tripAttachmentId() == null
                    || attachment.tripAttachmentId() <= 0 || attachment.score() == null
                    || !Double.isFinite(attachment.score()));
        if (invalid) {
            log.atWarn()
                    .addKeyValue("stage", "search")
                    .addKeyValue("failure", "response_contract")
                    .log("AI 검색 응답 계약 검증에 실패했습니다.");
            throw new AiQueryUnavailableException();
        }
    }
}
