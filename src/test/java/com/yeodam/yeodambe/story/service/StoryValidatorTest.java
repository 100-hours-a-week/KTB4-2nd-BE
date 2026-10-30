package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import com.yeodam.yeodambe.common.exception.AttachmentCannotBeReusedException;
import com.yeodam.yeodambe.story.service.request.StoryUpdateRequest;
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
                new StoryUpdateRequest.Block(null, 10L, null, 3),
                new StoryUpdateRequest.Block(1L, 11L, "메모", 1),
                new StoryUpdateRequest.Block(null, 12L, null, 2))))
                .doesNotThrowAnyException();
    }

    @Test
    void 같은_기존블록을_두번_요청하면_입력오류다() {
        assertThatThrownBy(() -> validator.validateUpdateBlocks(List.of(
                new StoryUpdateRequest.Block(1L, 10L, null, 1),
                new StoryUpdateRequest.Block(1L, 11L, null, 2))))
                .isInstanceOf(InvalidStoryUpdateException.class);
    }

    @Test
    void 다른_블록이어도_같은_사진을_사용하면_재사용충돌이다() {
        assertThatThrownBy(() -> validator.validateUpdateBlocks(List.of(
                new StoryUpdateRequest.Block(1L, 10L, null, 1),
                new StoryUpdateRequest.Block(null, 10L, null, 2))))
                .isInstanceOf(AttachmentCannotBeReusedException.class);
    }

    @Test
    void 순서의_중복과_범위초과와_누락을_거절한다() {
        for (int second : List.of(-1, 0, 1, 3)) {
            assertThatThrownBy(() -> validator.validateUpdateBlocks(List.of(
                    new StoryUpdateRequest.Block(1L, 10L, null, 1),
                    new StoryUpdateRequest.Block(null, 11L, null, second))))
                    .isInstanceOf(InvalidStoryUpdateException.class);
        }
    }

    private void invalidRelation(Row row) {
        assertThatThrownBy(() -> validator.validateBlockRelations(7L, List.of(row))).isInstanceOf(StoryDataIntegrityException.class);
    }

    private void invalidDisplay(Row row) {
        assertThatThrownBy(() -> validator.validateDisplayBlocks(7L, List.of(row))).isInstanceOf(StoryDataIntegrityException.class);
    }
}
