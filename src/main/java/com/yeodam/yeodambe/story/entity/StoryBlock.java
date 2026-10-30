package com.yeodam.yeodambe.story.entity;

import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import com.yeodam.yeodambe.common.validation.TextValidation;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "story_blocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryBlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "story_block_id")
    private Long id;

    @Column(name = "story_id", nullable = false)
    private Long storyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false, insertable = false, updatable = false)
    private Story story;

    @Column(name = "trip_attachment_id", nullable = false)
    private Long tripAttachmentId;

    @Column(name = "source_trip_place_id", nullable = false)
    private Long sourceTripPlaceId;

    @Column(name = "source_date", nullable = false)
    private LocalDate sourceDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_attachment_id", nullable = false, insertable = false, updatable = false)
    private TripAttachment attachment;

    @Column(name = "order_number", nullable = false)
    private int orderNumber;

    @Column(name = "day_label", nullable = false, length = 40)
    private String dayLabel;

    @Column(name = "detail_summary", nullable = false, length = 40)
    private String detailSummary;

    @Column(name = "memo", length = 125)
    private String memo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public void softDelete(LocalDateTime deletedAt) {
        if (this.deletedAt == null) {
            this.deletedAt = deletedAt;
        }
    }

    public void updateMemoAndOrder(String memo, int orderNumber) {
        if (orderNumber <= 0
                || (memo != null
                && memo.codePointCount(0, memo.length()) > 125)) {
            throw new InvalidStoryUpdateException();
        }

        this.memo = memo;
        this.orderNumber = orderNumber;
    }

    public void restore() {
        this.deletedAt = null;
    }

    public void updateContent(
            String dayLabel,
            String detailSummary,
            String memo,
            int orderNumber
    ) {
        if (!TextValidation.hasVisibleContent(dayLabel)
                || dayLabel.codePointCount(0, dayLabel.length()) > 40
                || !TextValidation.hasVisibleContent(detailSummary)
                || detailSummary.codePointCount(
                        0, detailSummary.length()
                ) > 40) {
            throw new InvalidStoryUpdateException();
        }

        updateMemoAndOrder(memo, orderNumber);
        this.dayLabel = dayLabel;
        this.detailSummary = detailSummary;
    }

    public void replaceAttachment(
            Long tripAttachmentId,
            Long sourceTripPlaceId,
            LocalDate sourceDate
    ) {
        if (tripAttachmentId == null || tripAttachmentId <= 0
                || sourceTripPlaceId == null || sourceTripPlaceId <= 0
                || sourceDate == null) {
            throw new InvalidStoryUpdateException();
        }

        this.tripAttachmentId = tripAttachmentId;
        this.sourceTripPlaceId = sourceTripPlaceId;
        this.sourceDate = sourceDate;
    }

    public StoryBlock(
            Long storyId,
            Long tripAttachmentId,
            Long sourceTripPlaceId,
            LocalDate sourceDate,
            int orderNumber,
            String dayLabel,
            String detailSummary,
            String memo
    ) {
        if (storyId == null || tripAttachmentId == null
                || sourceTripPlaceId == null || sourceDate == null) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.BLOCK_REFERENCE_REQUIRED.message()
            );
        }
        Story.validateText(dayLabel);
        Story.validateText(detailSummary);
        if (memo != null && memo.codePointCount(0, memo.length()) > 125) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.BLOCK_MEMO_TOO_LONG.message()
            );
        }
        this.storyId = storyId;
        this.tripAttachmentId = tripAttachmentId;
        this.sourceTripPlaceId = sourceTripPlaceId;
        this.sourceDate = sourceDate;
        this.orderNumber = orderNumber;
        this.dayLabel = dayLabel;
        this.detailSummary = detailSummary;
        this.memo = memo;
    }

    void connectStory(Story story) {
        if (story.getId() == null || !story.getId().equals(storyId)) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.SAVED_STORY_MISMATCH.message()
            );
        }
        this.storyId = story.getId();
        this.story = story;
    }
}
