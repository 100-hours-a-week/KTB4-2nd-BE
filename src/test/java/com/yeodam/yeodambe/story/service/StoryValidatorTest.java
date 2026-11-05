package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import com.yeodam.yeodambe.common.exception.AttachmentCannotBeReusedException;
import com.yeodam.yeodambe.common.exception.StoryOrAttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewTakenAtMissingException;
import com.yeodam.yeodambe.story.service.request.StoryUpdateRequest;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository.BlockDetail;
import com.yeodam.yeodambe.trip.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StoryValidatorTest {
    private final StoryValidator validator = new StoryValidator();

    @Test
    void 문서형_표시블록의_장소_날짜_미리보기경로를_검증한다() {
        Row row = row(1, 7, "날", null);
        var block = new com.yeodam.yeodambe.story.document.StoryContentBlock(
                1L, 1L, row.place().getId(), row.block().getSourceDate(),
                "날", "문구", null, null, null, null);
        var detail = new com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail(
                block, row.attachment(), row.file(), row.place());
        validator.validateContentDisplayBlocks(7L, List.of(detail));
        assertThatThrownBy(() -> validator.validateContentDisplayBlocks(7L, List.of(
                new com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail(
                        block, row.attachment(), row.file(), null))))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("표시 조건: 장소가 없습니다.");
        assertThatThrownBy(() -> validator.validateContentDisplayBlocks(8L, List.of(detail)))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("표시 조건: 다른 여행의 장소입니다.");
        var noDate = new com.yeodam.yeodambe.story.document.StoryContentBlock(
                1L, 1L, row.place().getId(), null, "날", "문구", null, null, null, null);
        assertThatThrownBy(() -> validator.validateContentDisplayBlocks(7L, List.of(
                new com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail(
                        noDate, row.attachment(), row.file(), row.place()))))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("표시 조건: 스토리 날짜가 없습니다.");
        for (String key : new String[]{null, "", " "}) {
            ReflectionTestUtils.setField(row.attachment(), "previewStorageKey", key);
            assertThatThrownBy(() -> validator.validateContentDisplayBlocks(7L, List.of(detail)))
                    .isInstanceOf(StoryDataIntegrityException.class)
                    .hasMessage("표시 조건: 미리보기 키가 없습니다.");
        }
    }

    @Test
    void 문서형_블록의_개수와_사진_파일_연결도_기존_기준으로_검증한다() {
        Row row = row(1, 7, "날", null);
        var block = new com.yeodam.yeodambe.story.document.StoryContentBlock(
                1L, 1L, row.place().getId(), row.block().getSourceDate(),
                "날", "문구", null, null, null, null);
        var valid = new com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail(
                block, row.attachment(), row.file(), row.place());
        validator.validateContentBlockRelations(7L, List.of(valid));
        validator.validateBlockCount(Collections.nCopies(30, valid));
        assertThatThrownBy(() -> validator.validateBlockCount(Collections.nCopies(31, valid)))
                .isInstanceOf(StoryDataIntegrityException.class);
        assertThatThrownBy(() -> validator.validateContentBlockRelations(7L, List.of(
                new com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail(
                        block, null, row.file(), row.place()))))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("블록 관계: 첨부가 없습니다.");
        assertThatThrownBy(() -> validator.validateContentBlockRelations(8L, List.of(valid)))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("블록 관계: 다른 여행의 첨부입니다.");
        assertThatThrownBy(() -> validator.validateContentBlockRelations(7L, List.of(
                new com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail(
                        block, row.attachment(), null, row.place()))))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("블록 관계: 파일이 없습니다.");
    }
    record Row(
            StoryBlock block,
            TripAttachment attachment,
            StoredFile file,
            TripDetailPlace place
    ) implements BlockDetail {
        @Override
        public StoryBlock getBlock() {
            return block;
        }

        @Override
        public TripAttachment getAttachment() {
            return attachment;
        }

        @Override
        public StoredFile getFile() {
            return file;
        }

        @Override
        public TripDetailPlace getPlace() {
            return place;
        }
    }

    static Row row(int order, int day, String label, String memo) {
        TripDetailPlace place = TripDetailPlace.fromAnalysis(
                7L, order, "장소", BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDateTime.of(2026, 10, day, 12, 0), LocalDateTime.of(2026, 10, day, 13, 0), null
        );
        ReflectionTestUtils.setField(place, "id", (long)day);
        TripAttachment attachment = TripAttachment.initial(7L, (long)order, "분석", "preview/"+order);
        attachment.classify((long)day, RegionOrigin.EXIF, place.getStartedAt(), BigDecimal.ZERO, BigDecimal.ZERO, 1);
        ReflectionTestUtils.setField(attachment, "id", (long)order);
        StoredFile file = StoredFile.uploaded(42L, "사진.jpg", "원본", "image/jpeg");
        StoryBlock block = new StoryBlock(1L, (long)order, place.getId(), place.getStartedAt().toLocalDate(),
                order, label, "문구"+order, memo);
        ReflectionTestUtils.setField(block, "id", (long)order);
        return new Row(block, attachment, file, place);
    }

    @Test
    void 전체_블록은_삭제필터_전_최대_30개다() {
        validator.validateBlockCount(List.of());
        validator.validateBlockCount(Collections.nCopies(30, row(1, 7, "날", null)));
        assertThatThrownBy(() -> validator.validateBlockCount(Collections.nCopies(31, row(1, 7, "날", null))))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("블록 개수: 최대 30개를 초과했습니다.");
    }

    @Test
    void 첨부와_파일_존재_및_첨부_여행소속을_검증한다() {
        Row valid = row(1, 7, "날", null);
        validator.validateBlockRelations(7L, List.of(valid));
        invalidRelation(new Row(valid.block(), null, valid.file(), valid.place()));
        invalidRelation(new Row(valid.block(), valid.attachment(), null, valid.place()));
        ReflectionTestUtils.setField(valid.attachment(), "tripId", 8L);
        invalidRelation(valid);
    }

    @Test
    void 표시_장소의_존재_소속_보존날짜와_미리보기키를_검증한다() {
        Row valid = row(1, 7, "날", null);
        validator.validateDisplayBlocks(7L, List.of(valid));
        invalidDisplay(new Row(valid.block(), valid.attachment(), valid.file(), null));
        ReflectionTestUtils.setField(valid.place(), "tripId", 8L);
        invalidDisplay(valid);
        ReflectionTestUtils.setField(valid.place(), "tripId", 7L);
        ReflectionTestUtils.setField(valid.block(), "sourceDate", null);
        invalidDisplay(valid);
        ReflectionTestUtils.setField(valid.block(), "sourceDate", java.time.LocalDate.of(2026, 10, 7));
        for (String key : new String[]{null, "", " "}) {
            ReflectionTestUtils.setField(valid.attachment(), "previewStorageKey", key);
            invalidDisplay(valid);
        }
    }

    @Test
    void 배열_순서와_무관하게_연속된_번호와_여러_신규블록을_허용한다() {
        assertThatCode(() -> validator.validateUpdateBlocks(List.of(
                new StoryUpdateRequest.Block(null, 10L, "첫째 날", "설명", null, 3),
                new StoryUpdateRequest.Block(1L, 11L, "첫째 날", "설명", "메모", 1),
                new StoryUpdateRequest.Block(null, 12L, "첫째 날", "설명", null, 2))))
                .doesNotThrowAnyException();
    }

    @Test
    void 같은_기존블록을_두번_요청하면_입력오류다() {
        assertThatThrownBy(() -> validator.validateUpdateBlocks(List.of(
                new StoryUpdateRequest.Block(1L, 10L, "첫째 날", "설명", null, 1),
                new StoryUpdateRequest.Block(1L, 11L, "첫째 날", "설명", null, 2))))
                .isInstanceOf(InvalidStoryUpdateException.class);
    }

    @Test
    void 다른_블록이어도_같은_사진을_사용하면_재사용충돌이다() {
        assertThatThrownBy(() -> validator.validateUpdateBlocks(List.of(
                new StoryUpdateRequest.Block(1L, 10L, "첫째 날", "설명", null, 1),
                new StoryUpdateRequest.Block(null, 10L, "첫째 날", "설명", null, 2))))
                .isInstanceOf(AttachmentCannotBeReusedException.class);
    }

    @Test
    void 순서의_중복과_범위초과와_누락을_거절한다() {
        for (int second : List.of(-1, 0, 1, 3)) {
            assertThatThrownBy(() -> validator.validateUpdateBlocks(List.of(
                    new StoryUpdateRequest.Block(1L, 10L, "첫째 날", "설명", null, 1),
                    new StoryUpdateRequest.Block(null, 11L, "첫째 날", "설명", null, second))))
                    .isInstanceOf(InvalidStoryUpdateException.class);
        }
    }

    @Test
    void 미리보기는_같은_여행의_촬영시각이_있는_ACTIVE사진을_허용한다() {
        var attachment = row(1, 7, "날", null).attachment();
        assertThatCode(() -> validator.validatePreviewAttachments(7L, 1, List.of(attachment)))
                .doesNotThrowAnyException();
    }

    @Test
    void 미리보기의_조회누락과_다른여행과_비활성사진은_404예외다() {
        assertThatThrownBy(() -> validator.validatePreviewAttachments(7L, 1, List.of()))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        var attachment = row(1, 7, "날", null).attachment();
        assertThatThrownBy(() -> validator.validatePreviewAttachments(8L, 1, List.of(attachment)))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        for (var status : ClassificationStatus.values()) {
            if (status == ClassificationStatus.ACTIVE) {
                continue;
            }
            ReflectionTestUtils.setField(attachment, "classificationStatus", status);
            assertThatThrownBy(() -> validator.validatePreviewAttachments(7L, 1, List.of(attachment)))
                    .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        }
    }

    @Test
    void 미리보기는_한장이라도_촬영시각이_없으면_전체를_거절한다() {
        var valid = row(1, 7, "날", null).attachment();
        var missing = row(2, 7, "날", null).attachment();
        ReflectionTestUtils.setField(missing, "takenAt", null);
        assertThatThrownBy(() -> validator.validatePreviewAttachments(7L, 2, List.of(valid, missing)))
                .isInstanceOf(StoryTonePreviewTakenAtMissingException.class);
    }

    @Test
    void 미리보기는_촬영시각보다_사진_접근조건을_먼저_검사한다() {
        var attachment = row(1, 7, "날", null).attachment();
        ReflectionTestUtils.setField(attachment, "takenAt", null);
        assertThatThrownBy(() -> validator.validatePreviewAttachments(8L, 1, List.of(attachment)))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
    }

    @Test
    void 미리보기는_기존블록과_ID없는_추가블록과_사진교체를_허용한다() {
        var existing = row(1, 7, "날", null).block();
        var requested = List.of(
                new StoryTonePreviewRequest.Block(existing.getId(), 99L, 1, null, null, null),
                new StoryTonePreviewRequest.Block(null, 100L, 2, null, null, null));
        assertThatCode(() -> validator.validatePreviewBlockIds(requested, java.util.Set.of(existing.getId())))
                .doesNotThrowAnyException();
    }

    @Test
    void 미리보기는_대상스토리_조회목록에_없는_블록ID를_거절한다() {
        var existing = row(1, 7, "날", null).block();
        var requested = List.of(
                new StoryTonePreviewRequest.Block(999L, 99L, 1, null, null, null));
        assertThatThrownBy(() -> validator.validatePreviewBlockIds(requested, java.util.Set.of(existing.getId())))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        assertThatThrownBy(() -> validator.validatePreviewBlockIds(requested, java.util.Set.of()))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
    }

    private void invalidRelation(Row row) {
        assertThatThrownBy(() -> validator.validateBlockRelations(7L, List.of(row))).isInstanceOf(StoryDataIntegrityException.class);
    }

    private void invalidDisplay(Row row) {
        assertThatThrownBy(() -> validator.validateDisplayBlocks(7L, List.of(row))).isInstanceOf(StoryDataIntegrityException.class);
    }
}
