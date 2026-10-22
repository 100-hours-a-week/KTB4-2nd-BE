package com.yeodam.yeodambe.search.service;

import com.yeodam.yeodambe.integration.exception.IntegrationInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import com.yeodam.yeodambe.integration.service.AiQueryService;
import com.yeodam.yeodambe.integration.service.request.AiQuerySearchRequest;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse;
import com.yeodam.yeodambe.integration.service.response.AiQuerySearchResponse;
import com.yeodam.yeodambe.search.service.response.SearchResponse;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripSearchCondition;
import com.yeodam.yeodambe.trip.service.response.TripSearchResultResponse;
import com.yeodam.yeodambe.trip.service.response.TripSearchCandidateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SearchService {
    private static final int SEARCH_LIMIT = 30;
    private static final int FOLDER_LIMIT = 10;
    private static final ZoneOffset SEARCH_OFFSET = ZoneOffset.ofHours(9);
    private final TripService tripService;
    private final AiQueryService aiQueryService;

    public SearchResponse search(Long userId, String query) {
        try (WorkflowMdcScope scope = openSearchLoggingContext();
             WorkflowLog.Stage timing = startSearchLogging()) {
            SearchResponse response = executeSearch(userId, query);
            logSearchCompleted(timing);
            return response;
        }
    }

    private WorkflowLog.Stage startSearchLogging() {
        return WorkflowLog.stage("search_total");
    }

    private void logSearchCompleted(WorkflowLog.Stage timing) {
        timing.success();
    }

    private WorkflowMdcScope openSearchLoggingContext() {
        Map<String, String> context = new HashMap<>();
        Map<String, String> current = MDC.getCopyOfContextMap();
        if (current != null) {
            context.putAll(current);
        }
        context.put("workflow", "natural_language_search");
        return WorkflowMdcScope.open(context);
    }

    private SearchResponse executeSearch(Long userId, String query) {
        AiQueryParseResponse parsed = aiQueryService.parse(query);

        TripSearchCondition condition = parsed.parsed()
                ? new TripSearchCondition(parsed.dateFrom(), parsed.dateTo(), parsed.regionNames())
                : new TripSearchCondition(null, null, null);
        List<TripSearchCandidateResponse> candidates = tripService.findSearchCandidates(userId, condition);

        if (candidates.isEmpty()) {
            return new SearchResponse(query, null, null, List.of(), List.of());
        }

        AiQuerySearchResponse searched = aiQueryService.search(new AiQuerySearchRequest(
                query,
                parsed.parsed() ? parsed.intent() : AiQueryParseResponse.Intent.SEARCH,
                resolveVisualQuery(parsed, query),
                candidates.stream().map(this::toAiCandidate).toList(), SEARCH_LIMIT
        ));

        validateCandidateMembership(candidates, searched.attachments());
        Map<Long, Double> scores = mergeDuplicateResults(searched.attachments());

        TripSearchResultResponse current = tripService.findSearchResults(userId, scores.keySet());

        Set<Long> validIds = current.attachments().stream()
                .map(TripSearchResultResponse.Attachment::tripAttachmentId)
                .collect(Collectors.toSet());

        String answer = validIds.containsAll(scores.keySet()) ? searched.answer() : null;

        return new SearchResponse(query, answer, searched.answerError(),
                buildFolders(current, scores), buildAttachments(current, scores));
    }

    private String resolveVisualQuery(AiQueryParseResponse parsed, String query) {
        if (!parsed.parsed() || parsed.visualQuery() == null || parsed.visualQuery().isBlank()) {
            return query;
        }
        return parsed.visualQuery();
    }

    private AiQuerySearchRequest.Candidate toAiCandidate(TripSearchCandidateResponse candidate) {
        return new AiQuerySearchRequest.Candidate(
                candidate.tripAttachmentId(), candidate.analyzeStorageKey(),
                candidate.takenAt() == null ? null : candidate.takenAt().atOffset(SEARCH_OFFSET),
                candidate.placeName(), candidate.tripName());
    }

    private void validateCandidateMembership(
            List<TripSearchCandidateResponse> candidates, List<AiQuerySearchResponse.Attachment> attachments
    ) {
        Set<Long> candidateIds = candidates.stream()
                .map(TripSearchCandidateResponse::tripAttachmentId)
                .collect(Collectors.toSet());
        boolean invalid = attachments.stream()
                .anyMatch(attachment -> !candidateIds.contains(attachment.tripAttachmentId()));
        if (invalid) {
            log.atWarn()
                    .addKeyValue("stage", "search")
                    .addKeyValue("failure", "candidate_membership")
                    .log(IntegrationInternalErrorMessage.AI_QUERY_RESULT_OUTSIDE_CANDIDATES.message());
            throw new AiQueryUnavailableException();
        }
    }

    private Map<Long, Double> mergeDuplicateResults(List<AiQuerySearchResponse.Attachment> attachments) {
        return attachments.stream().collect(Collectors.toMap(
                AiQuerySearchResponse.Attachment::tripAttachmentId,
                AiQuerySearchResponse.Attachment::score,
                Math::max
        ));
    }

    private List<SearchResponse.Attachment> buildAttachments(
            TripSearchResultResponse current, Map<Long, Double> scores
    ) {
        return current.attachments().stream()
                .map(attachment -> new SearchResponse.Attachment(
                        attachment.tripAttachmentId(),
                        attachment.tripId(),
                        attachment.tripName(),
                        attachment.tripPlaceId(),
                        attachment.thumbnailUrl(),
                        scores.get(attachment.tripAttachmentId())
                ))
                .sorted(Comparator.comparingDouble(SearchResponse.Attachment::score).reversed()
                        .thenComparing(SearchResponse.Attachment::tripAttachmentId))
                .limit(SEARCH_LIMIT)
                .toList();
    }

    private List<SearchResponse.Folder> buildFolders(
            TripSearchResultResponse current, Map<Long, Double> scores
    ) {
        Map<Long, Double> folderScores = current.attachments().stream()
                .filter(attachment -> attachment.tripPlaceId() != null)
                .collect(Collectors.toMap(TripSearchResultResponse.Attachment::tripPlaceId,
                        attachment -> scores.get(attachment.tripAttachmentId()), Math::max));

        return current.folders().stream()
                .filter(folder -> folderScores.containsKey(folder.tripPlaceId()))
                .map(folder -> new SearchResponse.Folder(
                        folder.tripId(), folder.tripPlaceId(), folder.tripName(), folder.placeName(),
                        folder.thumbnailUrl(), folder.attachmentCount(), folderScores.get(folder.tripPlaceId())))
                .sorted(Comparator.comparingDouble(SearchResponse.Folder::score).reversed()
                        .thenComparing(SearchResponse.Folder::tripPlaceId))
                .limit(FOLDER_LIMIT)
                .toList();
    }
}
