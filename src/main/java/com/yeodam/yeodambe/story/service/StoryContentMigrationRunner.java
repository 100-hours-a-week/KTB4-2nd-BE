package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StoryContentMigrationRunner implements ApplicationRunner {

    private static final int PAGE_SIZE = 100;

    private final StoryRepository storyRepository;
    private final StoryContentMigrationService migrationService;
    private final StoryContentRepository storyContentRepository;

    @Override
    public void run(ApplicationArguments args) {
        long afterId = 0L;
        long checkedCount = 0L;

        log.info("스토리 본문 자동 이관을 시작합니다.");

        while (true) {
            var storyIds = storyRepository.findContentMigrationTargetIds(
                    afterId,
                    PageRequest.of(0, PAGE_SIZE)
            );

            if (storyIds.isEmpty()) {
                break;
            }

            for (Long storyId : storyIds) {
                try {
                    migrationService.migrateOne(storyId);
                } catch (RuntimeException exception) {
                    log.error(
                            "스토리 본문 자동 이관 실패. story_id={} checked_count={}",
                            storyId,
                            checkedCount,
                            exception
                    );
                    throw exception;
                }

                checkedCount++;
                afterId = storyId;
            }
        }
        validateNoMissingContent();
        log.info(
                "스토리 본문 자동 이관 확인 완료. checked_count={} last_story_id={}",
                checkedCount,
                afterId
        );
    }
    private void validateNoMissingContent() {
        long afterId = 0L;

        while (true) {
            var storyIds = storyRepository.findContentMigrationTargetIds(
                    afterId,
                    PageRequest.of(0, PAGE_SIZE)
            );

            if (storyIds.isEmpty()) {
                return;
            }

            for (Long storyId : storyIds) {
                if (!storyContentRepository.existsById(storyId)) {
                    log.error("스토리 본문 이관 누락. story_id={}", storyId);
                    throw new StoryDataIntegrityException(
                            StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING
                    );
                }

                afterId = storyId;
            }
        }
    }
}