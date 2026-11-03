package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.RunpodStatusUnavailableException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewUnavailableException;
import com.yeodam.yeodambe.integration.service.RunpodStatusService;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import com.yeodam.yeodambe.story.client.StoryTonePreviewAiClient;
import com.yeodam.yeodambe.story.client.response.StoryTonePreviewAiResponse;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.story.service.response.StoryTonePreviewResponse;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StoryTonePreviewService {
    private final StoryTonePreviewInputService inputService;
    private final RunpodStatusService runpodStatusService;
    private final StoryTonePreviewAiClient aiClient;
    private final StoryTonePreviewResultValidator resultValidator;
    private final TripAttachmentStorageClient storageClient;

    public StoryTonePreviewResponse preview(
            Long userId,
            Long storyId,
            com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest request
    ) {
        StoryTonePreviewInputService.PreparedRequest prepared =
                inputService.prepare(userId, storyId, request);

        requireAiReady();

        StoryTonePreviewAiResponse response =
                aiClient.preview(prepared.tripId(), prepared.request());

        resultValidator.validate(prepared.request(), response);

        return new StoryTonePreviewResponse(
                storyId,
                prepared.request().mood(),
                response.storySummary(),
                buildDays(prepared.sources(), response.blocks())
        );
    }

    private void requireAiReady() {
        try {
            RunpodStatusResponse status = runpodStatusService.getStatus();

            if (!status.ready()) {
                throw new StoryTonePreviewUnavailableException();
            }
        } catch (RunpodStatusUnavailableException exception) {
            throw new StoryTonePreviewUnavailableException();
        }
    }

    private List<StoryDetailResponse.Day> buildDays(
            List<StoryTonePreviewInputService.BlockSource> sources,
            List<StoryTonePreviewAiResponse.Block> results
    ) {
        List<StoryDetailResponse.Day> days = new ArrayList<>();

        for (int index = 0; index < sources.size(); index++) {
            StoryTonePreviewInputService.BlockSource source =
                    sources.get(index);
            StoryTonePreviewAiResponse.Block result =
                    results.get(index);

            if (days.isEmpty()
                    || !source.date().equals(days.getLast().date())
                    || !result.dayLabel().equals(days.getLast().dayLabel())) {
                days.add(new StoryDetailResponse.Day(
                        source.date(),
                        result.dayLabel(),
                        new ArrayList<>()
                ));
            }

            String thumbnailUrl =
                    storageClient.createReadUrl(source.previewStorageKey());

            StoryDetailResponse.Block block =
                    new StoryDetailResponse.Block(
                            source.storyBlockId(),
                            source.orderNumber(),
                            source.tripPlaceId(),
                            source.tripAttachmentId(),
                            thumbnailUrl,
                            result.detailSummary(),
                            result.memo()
                    );

            days.getLast().blocks().add(block);
        }

        List<StoryDetailResponse.Day> responseDays = new ArrayList<>();

        for (StoryDetailResponse.Day day : days) {
            responseDays.add(new StoryDetailResponse.Day(
                    day.date(),
                    day.dayLabel(),
                    List.copyOf(day.blocks())
            ));
        }

        return List.copyOf(responseDays);
    }
}