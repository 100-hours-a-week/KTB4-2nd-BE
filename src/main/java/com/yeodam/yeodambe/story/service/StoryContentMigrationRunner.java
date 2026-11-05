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

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StoryContentMigrationRunner implements ApplicationRunner {

    private static final int PAGE_SIZE = 100;

    private final StoryRepository storyRepository;
    private final StoryContentMigrationService migrationService;

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

        log.info(
                "스토리 본문 자동 이관 확인 완료. checked_count={} last_story_id={}",
                checkedCount,
                afterId
        );
    }
}