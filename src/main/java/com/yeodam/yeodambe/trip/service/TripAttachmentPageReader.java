package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.common.exception.TripDetailNotAvailableException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository.PhotoReadState;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageQuery;
import com.yeodam.yeodambe.trip.service.request.cursor.AttachmentCursor;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageReadScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripAttachmentPageReader {

    private static final int PAGE_SIZE = 18;

    private final TripAccessService access;
    private final TripRepository trips;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentRepository attachments;
    private final TripAttachmentPageStore pages;
    private final TripAttachmentCacheProperties properties;
    private final ObjectMapper json;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public AttachmentPageData read(AttachmentPageQuery query) {
        return readValidated(prepareRead(query));
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public AttachmentPageReadScope prepareRead(AttachmentPageQuery query) {
        Trip trip;
        try {
            trip = access.requireReadableTrip(query.tripId(), query.userId());
        } catch (TripNotFoundException exception) {
            throw new PlaceFolderNotFoundException();
        }
        validateTarget(trip.getProcessingStatus(), query.tripPlaceId(), query.tripId());
        return new AttachmentPageReadScope(normalizeKey(query), trip.getSequence());
    }

    public AttachmentPageKey normalizeKey(AttachmentPageQuery query) {
        AttachmentCursor cursor = AttachmentCursor.decode(
                query.cursor(),
                query.tripId(),
                query.tripPlaceId(),
                null,
                json
        );
        String normalizedCursor = cursor == null ? null : cursor.encode(json);

        return new AttachmentPageKey(
                query.tripId(),
                query.tripPlaceId(),
                normalizedCursor
        );
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public AttachmentPageData readShared(AttachmentPageKey key) {
        PhotoReadState state = findTargetState(key.tripId());
        validateTarget(state.getProcessingStatus(), key.tripPlaceId(), key.tripId());
        return readValidated(new AttachmentPageReadScope(key, state.getSequence()));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public void validateTarget(Long tripId, Long tripPlaceId) {
        validateTarget(findTargetState(tripId).getProcessingStatus(), tripPlaceId, tripId);
    }

    private PhotoReadState findTargetState(Long tripId) {
        return trips.findPhotoReadStateById(tripId).orElseThrow(PlaceFolderNotFoundException::new);
    }

    private void validateTarget(ProcessingStatus status, Long tripPlaceId, Long tripId) {
        if (!places.existsByIdAndTripIdAndDeletedAtIsNull(tripPlaceId, tripId)) {
            throw new PlaceFolderNotFoundException();
        }
        if (status != ProcessingStatus.COMPLETED) {
            throw new TripDetailNotAvailableException();
        }
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public AttachmentPageData readValidated(AttachmentPageReadScope scope) {
        if (!properties.isEnabled()) {
            return loadDatabasePage(scope);
        }
        try {
            Optional<AttachmentPageData> cached = pages.find(scope.key(), scope.generation());
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
            logCacheFailure(exception);
            return loadDatabasePage(scope);
        }
        AttachmentPageData page = loadDatabasePage(scope);
        try {
            pages.save(scope.key(), page);
        } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
            logCacheFailure(exception);
        }
        return page;
    }

    private AttachmentPageData loadDatabasePage(AttachmentPageReadScope scope) {
        AttachmentPageKey query = scope.key();
        AttachmentCursor cursor = decode(query);
        List<TripAttachment> found = attachments.findByPlaceFolderWithCursor(
                query.tripId(), query.tripPlaceId(), ClassificationStatus.ACTIVE,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.tripAttachmentId(), PageRequest.of(0, PAGE_SIZE + 1));
        Set<Long> pending = Set.copyOf(attachments.findPendingDeletionIds(found.stream().map(TripAttachment::getId).toList()));
        List<AttachmentPageData.Item> items = found.stream().limit(PAGE_SIZE)
                .map(attachment -> new AttachmentPageData.Item(
                        attachment.getId(), attachment.getCreatedAt(), attachment.getPreviewStorageKey(),
                        pending.contains(attachment.getId())))
                .toList();
        boolean hasNext = found.size() > PAGE_SIZE;
        String nextCursor = null;
        if (hasNext) {
            TripAttachment last = found.get(PAGE_SIZE - 1);
            nextCursor = new AttachmentCursor(last.getCreatedAt(), last.getId(),
                    query.tripId(), query.tripPlaceId(), null).encode(json);
        }
        return new AttachmentPageData(items, hasNext, nextCursor, scope.generation());
    }

    private AttachmentCursor decode(AttachmentPageKey query) {
        return AttachmentCursor.decode(query.cursor(), query.tripId(), query.tripPlaceId(), null, json);
    }

    private void logCacheFailure(RuntimeException exception) {
        log.atWarn().addKeyValue("event", "photo_metadata_cache_failure")
                .addKeyValue("failure", exception.getClass().getSimpleName())
                .log(TripInternalErrorMessage.PHOTO_LIST_METADATA_CACHE_UNAVAILABLE.message());
    }
}
