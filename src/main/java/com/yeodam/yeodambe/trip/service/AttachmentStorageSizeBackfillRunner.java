package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.storage-size-backfill.enabled",
        havingValue = "true"
)
public class AttachmentStorageSizeBackfillRunner
        implements ApplicationRunner {

    private final TripAttachmentRepository attachments;
    private final AttachmentStorageSizeBackfillService backfill;

    @Override
    public void run(ApplicationArguments args) {
        long afterId = 0L;

        while (true) {
            var batch = attachments.findMissingStorageSizes(
                    afterId, PageRequest.of(0, 100));

            if (batch.isEmpty()) {
                log.info("첨부 용량 백필 대상 처리 종료");
                return;
            }

            for (var attachment : batch) {
                log.info("첨부 용량 백필 처리 attachment_id={}",
                        attachment.getId());

                backfill.backfillOne(attachment.getId());
                afterId = attachment.getId();
            }
        }
    }
}