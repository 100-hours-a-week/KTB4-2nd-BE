package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository.BlockDetail;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StoryValidator {

    public void validateBlockCount(List<BlockDetail> blocks) {
        if (blocks.size() > 10) {
            throw new StoryDataIntegrityException("블록 개수: 최대 10개를 초과했습니다.");
        }
    }

    public void validateBlockRelations(Long tripId, List<BlockDetail> blocks) {
        blocks.forEach(block -> validateBlockRelation(tripId, block));
    }

    public void validateDisplayBlocks(Long tripId, List<BlockDetail> blocks) {
        blocks.forEach(block -> validateDisplayBlock(tripId, block));
    }

    private void validateBlockRelation(Long tripId, BlockDetail block) {
        if (block.getAttachment() == null) {
            throw new StoryDataIntegrityException("블록 관계: 첨부가 없습니다.");
        }
        if (!tripId.equals(block.getAttachment().getTripId())) {
            throw new StoryDataIntegrityException("블록 관계: 다른 여행의 첨부입니다.");
        }
        if (block.getFile() == null) {
            throw new StoryDataIntegrityException("블록 관계: 파일이 없습니다.");
        }
    }

    private void validateDisplayBlock(Long tripId, BlockDetail block) {
        if (block.getPlace() == null) {
            throw new StoryDataIntegrityException("표시 조건: 장소가 없습니다.");
        }
        if (!tripId.equals(block.getPlace().getTripId())) {
            throw new StoryDataIntegrityException("표시 조건: 다른 여행의 장소입니다.");
        }
        if (block.getPlace().getStartedAt() == null) {
            throw new StoryDataIntegrityException("표시 조건: 장소 시작일이 없습니다.");
        }
        String key = block.getAttachment().getPreviewStorageKey();
        if (key == null || key.isBlank()) {
            throw new StoryDataIntegrityException("표시 조건: 미리보기 키가 없습니다.");
        }
    }
}
