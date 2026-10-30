package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentDeleteNotAllowedException;
import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripUpdateNotAllowedException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.common.response.ErrorMessage;

public final class TripEditRejectionMessageResolver {
    private TripEditRejectionMessageResolver() {
    }

    public static ErrorMessage resolve(RuntimeException exception) {
        return switch (exception) {
            case InvalidTripRequestException ignored -> ErrorMessage.INVALID_TRIP_REQUEST;
            case InvalidAttachmentIdsException ignored -> ErrorMessage.INVALID_ATTACHMENT_IDS;
            case TripNotFoundException ignored -> ErrorMessage.TRIP_NOT_FOUND;
            case AttachmentNotFoundException ignored -> ErrorMessage.ATTACHMENT_NOT_FOUND;
            case WritePermissionRequiredException ignored -> ErrorMessage.WRITE_PERMISSION_REQUIRED;
            case TripNameDuplicatedException ignored -> ErrorMessage.TRIP_NAME_DUPLICATED;
            case TripUpdateNotAllowedException ignored -> ErrorMessage.TRIP_UPDATE_NOT_ALLOWED;
            case TripAttachmentAddNotAllowedException ignored -> ErrorMessage.TRIP_ATTACHMENT_ADD_NOT_ALLOWED;
            case AttachmentDeleteNotAllowedException ignored -> ErrorMessage.ATTACHMENT_DELETE_NOT_ALLOWED;
            default -> null;
        };
    }
}
