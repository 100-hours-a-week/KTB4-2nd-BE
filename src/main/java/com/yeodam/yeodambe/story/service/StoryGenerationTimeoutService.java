package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryGenerationTimeoutService {
    private final StoryGenerationJobRepository jobRepository;

    @Transactional
    public boolean expire(Long generationId, LocalDateTime now) {
        var job = jobRepository.findByIdForUpdate(generationId).orElse(null);
        if (job == null || job.isFinished()) {
            return true;
        }
        if (!job.failIfTimedOut(now)) {
            return false;
        }
        log.atWarn()
                .addKeyValue("event", "story_generation_timed_out")
                .addKeyValue("result", "failure")
                .addKeyValue("trip_id", job.getTripId())
                .addKeyValue("job_id", job.getId())
                .addKeyValue("error_code", job.getErrorCode())
                .log("스토리 생성 접수 후 1시간이 지나 작업을 실패 처리했습니다.");
        return true;
    }
}
