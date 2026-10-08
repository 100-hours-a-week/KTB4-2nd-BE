package com.yeodam.yeodambe.story.entity;

import com.yeodam.yeodambe.trip.entity.TripAttachment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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

    public StoryBlock(
            Long storyId,
            Long tripAttachmentId,
            int orderNumber,
            String dayLabel,
            String detailSummary,
            String memo
    ) {
        if (storyId == null || tripAttachmentId == null) {
            throw new IllegalArgumentException("블록 참조가 필요합니다.");
        }
        Story.validateText(dayLabel);
        Story.validateText(detailSummary);
        if (memo != null && memo.length() > 125) {
            throw new IllegalArgumentException("메모는 최대 125자입니다.");
        }
        this.storyId = storyId;
        this.tripAttachmentId = tripAttachmentId;
        this.orderNumber = orderNumber;
        this.dayLabel = dayLabel;
        this.detailSummary = detailSummary;
        this.memo = memo;
    }

    void connectStory(Story story) {
        if (story.getId() == null || !story.getId().equals(storyId)) {
            throw new IllegalArgumentException("저장된 같은 스토리에만 연결할 수 있습니다.");
        }
        this.storyId = story.getId();
        this.story = story;
    }
}
