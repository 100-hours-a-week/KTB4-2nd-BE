package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AttachmentDerivativePreparationService {

    private final TripAttachmentDerivativeService tripAttachmentDerivativeService;

    public List<DerivedPhotoKeys> create(
            String executionId,
            List<String> originalKeys,
            List<String> contentTypes
    ) {
        List<DerivedPhotoKeys> derived =
                tripAttachmentDerivativeService.createAll(
                        executionId,
                        originalKeys,
                        contentTypes
                ).join();

        if (derived == null || derived.size() != originalKeys.size()) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.DERIVED_ATTACHMENT_COUNT_MISMATCH.message()
            );
        }

        for (int index = 0; index < derived.size(); index++) {
            DerivedPhotoKeys photo = derived.get(index);

            if (photo == null
                    || !originalKeys.get(index).equals(photo.originalKey())
                    || photo.analyzeKey() == null
                    || photo.analyzeKey().isBlank()
                    || photo.previewKey() == null
                    || photo.previewKey().isBlank()
                    || ("image/heic".equals(contentTypes.get(index))
                    && (photo.displayKey() == null
                    || photo.displayKey().isBlank()))) {
                throw new IllegalStateException(
                        TripInternalErrorMessage.DERIVED_ATTACHMENT_RESULT_INVALID.message()
                );
            }
        }

        return derived;
    }
}