package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class StoryBlockReferenceQuery {
    private static final String FIND_REFERENCES = """
            select input.ordinal,
                   a.trip_attachment_id as attachment_id, a.trip_id as attachment_trip_id,
                   a.deleted_at as attachment_deleted_at,
                   f.file_id, f.deleted_at as file_deleted_at,
                   p.trip_place_id as place_id, p.trip_id as place_trip_id,
                   a.preview_storage_key as current_preview_storage_key
            from JSON_TABLE(?, '$[*]' columns (
                ordinal for ordinality,
                attachment_id bigint path '$.tripAttachmentId' null on empty error on error,
                source_place_id bigint path '$.sourceTripPlaceId' null on empty error on error
            )) as input
            left join trip_attachments a on a.trip_attachment_id = input.attachment_id
            left join files f on f.file_id = a.file_id
            left join trip_detail_places p on p.trip_place_id = input.source_place_id
            order by input.ordinal
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public List<StoryContentBlockDetail> findByBlocks(List<StoryContentBlock> blocks) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        if (blocks.size() > 30) {
            throw new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.BLOCK_COUNT_EXCEEDED);
        }
        List<BlockReferenceInput> inputs = blocks.stream()
                .map(block -> new BlockReferenceInput(block.tripAttachmentId(), block.sourceTripPlaceId()))
                .toList();
        String json = objectMapper.writeValueAsString(inputs);
        List<StoryContentBlockDetail> details = jdbc.query(FIND_REFERENCES, (row, index) ->
                new StoryContentBlockDetail(
                        blocks.get(row.getInt("ordinal") - 1),
                        row.getObject("attachment_id", Long.class),
                        row.getObject("attachment_trip_id", Long.class),
                        row.getObject("attachment_deleted_at", LocalDateTime.class),
                        row.getObject("file_id", Long.class),
                        row.getObject("file_deleted_at", LocalDateTime.class),
                        row.getObject("place_id", Long.class),
                        row.getObject("place_trip_id", Long.class),
                        row.getString("current_preview_storage_key")
                ), json);
        if (details.size() != blocks.size()) {
            throw new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.READ_DATA_INTEGRITY_FAILED);
        }
        return details;
    }

    private record BlockReferenceInput(
            Long tripAttachmentId,
            Long sourceTripPlaceId
    ) {
    }
}
