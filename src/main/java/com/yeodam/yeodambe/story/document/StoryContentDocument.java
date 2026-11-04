package com.yeodam.yeodambe.story.document;

import com.yeodam.yeodambe.story.entity.Story;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "story_contents")
public class StoryContentDocument {

    @Id
    private Long id;

    private Story.Mood mood;

    private String storySummary;

    private List<Long> selectedPlaceIds;

    private List<StoryContentBlock> blocks;

    private Long nextBlockId;

    @Version
    private Long version;

    public StoryContentDocument(
            Long id,
            Story.Mood mood,
            String storySummary,
            List<Long> selectedPlaceIds,
            List<StoryContentBlock> blocks,
            Long nextBlockId
    ) {
        this.id = id;
        this.mood = mood;
        this.storySummary = storySummary;
        this.selectedPlaceIds = List.copyOf(selectedPlaceIds);
        this.blocks = List.copyOf(blocks);
        this.nextBlockId = nextBlockId;
    }

    public long issueBlockId() {
        long blockId = nextBlockId;
        nextBlockId = blockId + 1;
        return blockId;
    }

    public void updateContent(
            Story.Mood mood,
            String storySummary,
            List<StoryContentBlock> blocks
    ) {
        this.mood = mood;
        this.storySummary = storySummary;
        this.blocks = List.copyOf(blocks);
    }
}