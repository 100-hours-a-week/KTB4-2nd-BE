package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageQuery;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TripAttachmentListService {

    private final TripAttachmentPageReader reader;
    private final TripAttachmentStorageClient storage;
    private final TripAttachmentCacheProperties properties;
    private final TripAttachmentResponseCacheService responseCache;

    public TripAttachmentListResponse findByPlaceFolder(
            Long userId,
            Long tripId,
            Long tripPlaceId,
            String cursor
    ) {
        AttachmentPageQuery query = new AttachmentPageQuery(userId, tripId, tripPlaceId, cursor);
        if (properties.isEnabled()) {
            return responseCache.read(query, this::toResponse);
        }
        return toResponse(reader.read(query));
    }

    private TripAttachmentListResponse toResponse(AttachmentPageData page) {
        List<TripAttachmentListResponse.Item> items = page.items().stream()
                .map(item -> new TripAttachmentListResponse.Item(
                        item.tripAttachmentId(), storage.createReadUrl(item.previewStorageKey()), item.pendingDeletion()))
                .toList();
        return new TripAttachmentListResponse(items, page.hasNext(), page.nextCursor(), page.sequence());
    }
}
