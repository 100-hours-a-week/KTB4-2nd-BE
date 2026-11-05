package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

import java.util.function.LongSupplier;

public final class AttachmentReadContext {

    private final LongSupplier nanoTime;
    private boolean missed;
    private long deadlineNanos;
    private int accessRefreshes;
    private int responseRefreshes;

    AttachmentReadContext(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public void onCacheMiss() {
        if (!missed) {
            missed = true;
            deadlineNanos = nanoTime.getAsLong() + 2_000_000_000L;
        }
    }

    public long remainingWaitNanos() {
        if (!missed) {
            throw new IllegalStateException(TripInternalErrorMessage.PHOTO_LIST_WAIT_NOT_STARTED.message());
        }
        return deadlineNanos - nanoTime.getAsLong();
    }

    public void requireWaitBudget() {
        if (missed && remainingWaitNanos() <= 0L) {
            throw new AttachmentReadRejectedException(AttachmentReadRejectedException.Reason.TIMEOUT);
        }
    }

    public void beginAccessRefresh() {
        requireAttempts(++accessRefreshes);
    }

    public void beginResponseRefresh() {
        requireAttempts(++responseRefreshes);
    }

    private void requireAttempts(int attempts) {
        requireWaitBudget();
        if (attempts > 2) {
            throw new AttachmentReadRejectedException(AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED);
        }
    }
}
