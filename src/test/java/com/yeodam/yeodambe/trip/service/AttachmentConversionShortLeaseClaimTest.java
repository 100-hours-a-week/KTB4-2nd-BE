package com.yeodam.yeodambe.trip.service;

import org.springframework.boot.test.context.SpringBootTest;

/** Re-run the actual MySQL ownership, stale write and cleanup contracts with the shorter policy. */
@SpringBootTest(properties = {
        "spring.application.name=conversion-short-lease-test",
        "app.attachment-conversion.lease.duration=120s",
        "app.attachment-conversion.lease.renew-interval=30s"
})
class AttachmentConversionShortLeaseClaimTest extends AttachmentConversionClaimTest {
}
