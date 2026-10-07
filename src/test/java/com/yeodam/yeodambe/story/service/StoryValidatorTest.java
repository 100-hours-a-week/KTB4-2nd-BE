package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.exception.StoryDataIntegrityException;
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
        StoryBlock block = new StoryBlock(1L, (long)order, order, label, "문구"+order, memo);
        ReflectionTestUtils.setField(block, "id", (long)order);
        return new Row(block, attachment, file, place);
    }

    @Test
    void 전체_블록은_삭제필터_전_최대_10개다() {
        validator.validateBlockCount(List.of());
        validator.validateBlockCount(Collections.nCopies(10, row(1, 7, "날", null)));
        assertThatThrownBy(() -> validator.validateBlockCount(Collections.nCopies(11, row(1, 7, "날", null))))
                .isInstanceOf(StoryDataIntegrityException.class);
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
    void 표시_장소의_존재_소속_시작일과_미리보기키를_검증한다() {
        Row valid = row(1, 7, "날", null);
        validator.validateDisplayBlocks(7L, List.of(valid));
        invalidDisplay(new Row(valid.block(), valid.attachment(), valid.file(), null));
        ReflectionTestUtils.setField(valid.place(), "tripId", 8L);
        invalidDisplay(valid);
        ReflectionTestUtils.setField(valid.place(), "tripId", 7L);
        ReflectionTestUtils.setField(valid.place(), "startedAt", null);
        invalidDisplay(valid);
        ReflectionTestUtils.setField(valid.place(), "startedAt", LocalDateTime.now());
        for (String key : new String[]{null, "", " "}) {
            ReflectionTestUtils.setField(valid.attachment(), "previewStorageKey", key);
            invalidDisplay(valid);
        }
    }

    private void invalidRelation(Row row) {
        assertThatThrownBy(() -> validator.validateBlockRelations(7L, List.of(row))).isInstanceOf(StoryDataIntegrityException.class);
    }

    private void invalidDisplay(Row row) {
        assertThatThrownBy(() -> validator.validateDisplayBlocks(7L, List.of(row))).isInstanceOf(StoryDataIntegrityException.class);
    }
}
