package com.yeodam.yeodambe.story.document;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import java.time.Instant;
import java.time.LocalDate;

public record StoryContentBlock(
        Long id,
        Long tripAttachmentId,
        Long sourceTripPlaceId,

        @Field(targetType = FieldType.STRING)
        LocalDate sourceDate,

        String dayLabel,
        String detailSummary,
        String memo,

        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}