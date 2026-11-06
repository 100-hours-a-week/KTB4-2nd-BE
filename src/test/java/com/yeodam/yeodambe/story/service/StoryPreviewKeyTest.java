package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.story.repository.StoryBlockReferenceQuery;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.service.TripEditAuthorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StoryPreviewKeyTest {
    private final StoryBlockReferenceQuery references = mock(StoryBlockReferenceQuery.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final StoryReadService reader = new StoryReadService(
            mock(TripEditAuthorizationService.class), mock(StoryRepository.class), new StoryValidator(),
            storage, mock(StoryContentRepository.class), references
    );

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "preview/old", "preview/1"})
    void 누락되거나_오래된_Mongo키는_SQL최신키로_응답한다(String key) {
        StoryValidatorTest.Row row = StoryValidatorTest.row(1, 7, "날", null);
        ReflectionTestUtils.setField(row.attachment(), "file", row.file());
        when(storage.createReadUrl("preview/1")).thenReturn("signed-url");
        StoryContentBlock block = new StoryContentBlock(
                1L, 1L, 7L, row.block().getSourceDate(), "날", "설명", null,
                null, null, null, key
        );
        when(references.findByBlocks(List.of(block))).thenReturn(List.of(
                StoryValidatorTest.contentDetail(block, row.attachment(), row.file(), row.place())));
        StoryContentDocument document = new StoryContentDocument(
                1L, Story.Mood.PLAIN, "요약", List.of(7L), List.of(block), 2L
        );

        StoryDetailResponse response = reader.prepareContentResponse(7L, true, document);

        assertThat(response.days().getFirst().blocks().getFirst().thumbnailUrl()).isEqualTo("signed-url");
        assertThat(document.getBlocks().getFirst().previewStorageKey()).isEqualTo(key);
        assertThat(document.getVersion()).isNull();
        verify(storage).createReadUrl("preview/1");
    }

    @Test
    void SQL최신키가_없으면_Mongo키로_우회하지_않는다() {
        StoryValidatorTest.Row row = StoryValidatorTest.row(1, 7, "날", null);
        ReflectionTestUtils.setField(row.attachment(), "file", row.file());
        ReflectionTestUtils.setField(row.attachment(), "previewStorageKey", null);
        StoryContentBlock block = new StoryContentBlock(
                1L, 1L, 7L, row.block().getSourceDate(), "날", "설명", null,
                null, null, null, "preview/old"
        );

        when(references.findByBlocks(List.of(block))).thenReturn(List.of(
                StoryValidatorTest.contentDetail(block, row.attachment(), row.file(), row.place())));
        assertThatThrownBy(() -> reader.prepareContentResponse(7L, true, new StoryContentDocument(
                1L, Story.Mood.PLAIN, "요약", List.of(7L), List.of(block), 2L
        ))).isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.DISPLAY_PREVIEW_KEY_MISSING.message());
        verifyNoInteractions(storage);
    }

}
