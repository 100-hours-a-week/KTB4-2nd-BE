package com.yeodam.yeodambe.common.exception;

import lombok.Getter;

@Getter
public class AiProcessingFailedException extends RuntimeException {
    private final Long tripId;
    private final int done;
    private final int total;
    private final String currentStep;
    private final String code;
    private final String publicMessage;

    public AiProcessingFailedException(
            Long tripId,
            int done,
            int total,
            String currentStep,
            String code,
            String publicMessage
    ) {
        super(publicMessage);
        this.tripId = tripId;
        this.done = done;
        this.total = total;
        this.currentStep = currentStep;
        this.code = code;
        this.publicMessage = publicMessage;
    }
}
