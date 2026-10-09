package com.yeodam.yeodambe.search.service;

import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
import com.yeodam.yeodambe.integration.service.AiQueryService;
import com.yeodam.yeodambe.integration.service.request.AiQuerySearchRequest;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse;
import com.yeodam.yeodambe.integration.service.response.AiQuerySearchResponse;
import com.yeodam.yeodambe.search.service.response.SearchResponse;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripSearchCondition;
import com.yeodam.yeodambe.trip.service.response.TripSearchResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SearchService {
    private static final int SEARCH_LIMIT = 30;
    private final TripService tripService;
    private final AiQueryService aiQueryService;

    public SearchResponse search(Long userId, String query) {
        AiQueryParseResponse parsed = aiQueryService.parse(query);

        List<Long> candidates = tripService.findSearchCandidateIds(userId, new TripSearchCondition(
                parsed.dateFrom(), parsed.dateTo(), parsed.regionNames()
        ));

        if (candidates.isEmpty()) {
            return new SearchResponse(query, null, null, List.of(), List.of());
        }

        AiQuerySearchResponse searched = aiQueryService.search(new AiQuerySearchRequest(
                parsed.intent(), parsed.visualQuery(), candidates, SEARCH_LIMIT
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

    private void validateCandidateMembership(
            List<Long> candidates, List<AiQuerySearchResponse.Attachment> attachments
    ) {
        Set<Long> candidateIds = new HashSet<>(candidates);
        boolean invalid = attachments.stream()
                .anyMatch(attachment -> !candidateIds.contains(attachment.tripAttachmentId()));
        if (invalid) {
            log.atWarn()
                    .addKeyValue("stage", "search")
                    .addKeyValue("failure", "candidate_membership")
                    .log("AI 검색 응답에 후보 외 사진이 포함됐습니다.");
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
                        attachment.tripAttachmentId(), attachment.tripId(), attachment.tripPlaceId(),
                        attachment.thumbnailUrl(), scores.get(attachment.tripAttachmentId())))
                .sorted(Comparator.comparingDouble(SearchResponse.Attachment::score).reversed()
                        .thenComparing(SearchResponse.Attachment::tripAttachmentId))
                .limit(SEARCH_LIMIT)
                .toList();
    }

    private List<SearchResponse.Folder> buildFolders(
            TripSearchResultResponse current, Map<Long, Double> scores
    ) {
        Map<Long, Double> folderScores = current.attachments().stream()
                .collect(Collectors.toMap(TripSearchResultResponse.Attachment::tripId,
                        attachment -> scores.get(attachment.tripAttachmentId()), Math::max));

        return current.folders().stream()
                .filter(folder -> folderScores.containsKey(folder.tripId()))
                .map(folder -> new SearchResponse.Folder(
                        folder.tripId(), folder.tripName(), folder.startDate(), folder.endDate(),
                        folder.regionNames(), folder.attachmentCount(), folder.thumbnailUrl(),
                        folderScores.get(folder.tripId())))
                .sorted(Comparator.comparingDouble(SearchResponse.Folder::score).reversed()
                        .thenComparing(SearchResponse.Folder::tripId))
                .limit(SEARCH_LIMIT)
                .toList();
    }
}
