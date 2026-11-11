package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.InvalidRestoreRequestException;
import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.RestorePlaceFolderRequiredException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord.CommandType;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.request.BulkAttachmentRestoreRequest;
import com.yeodam.yeodambe.trip.service.request.TripEditPhotoDeleteCommandRequest;
import com.yeodam.yeodambe.trip.service.request.TripEditPhotoRestoreCommandRequest;
import com.yeodam.yeodambe.trip.service.request.TripEditUpdateCommandRequest;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import com.yeodam.yeodambe.trip.service.response.TripEditCommandResultResponse;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class TripEditCommandService {
    private static final Set<String> UPDATE_FIELDS = Set.of("tripName", "startDate", "endDate", "regionCodes");
    private static final Set<String> RESTORE_FIELDS = Set.of("tripAttachmentId", "tripPlaceId");
    private final TripEditCommandTransactionService transactions;
    private final TripEditAccessService access;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final TripEditLock editLock;

    public ApiResponse<TripEditCommandResultResponse> update(
            Long tripId, Long actorId, TripEditUpdateCommandRequest command
    ) {
        String hash = fingerprint(command.changes());
        return executeCommand(tripId, actorId, command.requestId(), CommandType.UPDATE_TRIP, hash,
                () -> parseUpdate(command.changes()),
                request -> transactions.update(tripId, actorId, command.requestId(), request, hash));
    }

    public ApiResponse<TripEditCommandResultResponse> deleteAttachments(
            Long tripId, Long actorId, TripEditPhotoDeleteCommandRequest command
    ) {
        String hash = fingerprint(command.attachmentIds());
        return executeCommand(tripId, actorId, command.requestId(), CommandType.DELETE_ATTACHMENTS, hash,
                () -> parseAttachmentIds(command.attachmentIds()),
                ids -> transactions.deleteAttachments(tripId, actorId, command.requestId(), ids, hash));
    }

    public ApiResponse<TripEditCommandResultResponse> restoreAttachments(
            Long tripId, Long actorId, TripEditPhotoRestoreCommandRequest command
    ) {
        String hash = fingerprint(command.items());
        return executeCommand(tripId, actorId, command.requestId(), CommandType.RESTORE_ATTACHMENTS, hash,
                () -> parseRestores(command.items()),
                items -> transactions.restoreAttachments(tripId, actorId, command.requestId(), items, hash));
    }

    private <T> ApiResponse<TripEditCommandResultResponse> executeCommand(
            Long tripId,
            Long actorId,
            String requestId,
            CommandType type,
            String hash,
            Supplier<T> parse,
            Function<T, ApiResponse<TripEditCommandResultResponse>> operation
    ) {
        try {
            return authorizeAndExecute(tripId, actorId, requestId, type, hash, parse, operation);
        } catch (DataIntegrityViolationException exception) {
            return editLock.execute(tripId, () -> transactions.replayExistingResult(tripId, actorId, requestId, type, hash)
                    .orElseThrow(() -> exception));
        }
    }

    private <T> ApiResponse<TripEditCommandResultResponse> authorizeAndExecute(
            Long tripId,
            Long actorId,
            String requestId,
            CommandType type,
            String hash,
            Supplier<T> parse,
            Function<T, ApiResponse<TripEditCommandResultResponse>> operation
    ) {
        try {
            access.requireWritePermission(tripId, actorId);
        } catch (RuntimeException exception) {
            ErrorMessage message = TripEditRejectionMessageResolver.resolve(exception);
            if (message == null) {
                throw exception;
            }
            return editLock.execute(tripId, () -> transactions.reject(tripId, actorId, requestId, type, hash, message));
        }
        T parsed;
        try {
            parsed = parse.get();
        } catch (RuntimeException exception) {
            ErrorMessage message = TripEditRejectionMessageResolver.resolve(exception);
            if (message == null) {
                throw exception;
            }
            return editLock.execute(tripId,
                    () -> transactions.reject(tripId, actorId, requestId, type, hash, message));
        }
        return editLock.execute(tripId, () -> {
            try {
                return operation.apply(parsed);
            } catch (RuntimeException exception) {
                ErrorMessage message = TripEditRejectionMessageResolver.resolve(exception);
                if (message == null) {
                    throw exception;
                }
                return transactions.reject(tripId, actorId, requestId, type, hash, message);
            }
        });
    }

    private List<Long> parseAttachmentIds(JsonNode attachmentIds) {
        if (attachmentIds == null || !attachmentIds.isArray()
                || !StreamSupport.stream(attachmentIds.spliterator(), false)
                .allMatch(id -> id.isIntegralNumber() && id.canConvertToLong())) {
            throw new InvalidAttachmentIdsException();
        }
        return StreamSupport.stream(attachmentIds.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private List<BulkAttachmentRestoreRequest.Item> parseRestores(JsonNode items) {
        if (items == null || !items.isArray()) {
            throw new InvalidRestoreRequestException();
        }
        List<BulkAttachmentRestoreRequest.Item> parsed = StreamSupport.stream(items.spliterator(), false)
                .map(this::parseRestoreItem)
                .toList();
        if (parsed.stream().anyMatch(item -> item.tripPlaceId() == null)) {
            throw new RestorePlaceFolderRequiredException();
        }
        if (!validator.validate(new BulkAttachmentRestoreRequest(parsed)).isEmpty()) {
            throw new InvalidRestoreRequestException();
        }
        return parsed;
    }

    private BulkAttachmentRestoreRequest.Item parseRestoreItem(JsonNode item) {
        if (!item.isObject() || item.properties().stream().anyMatch(field -> !RESTORE_FIELDS.contains(field.getKey()))
                || !validLong(item.get("tripAttachmentId"))) {
            throw new InvalidRestoreRequestException();
        }
        JsonNode destination = item.get("tripPlaceId");
        if (destination != null && !destination.isNull() && !validLong(destination)) {
            throw new InvalidRestoreRequestException();
        }
        return new BulkAttachmentRestoreRequest.Item(item.get("tripAttachmentId").asLong(),
                destination == null || destination.isNull() ? null : destination.asLong());
    }

    private boolean validLong(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong();
    }

    private TripUpdateRequest parseUpdate(JsonNode changes) {
        if (changes == null || !changes.isObject() || changes.isEmpty()
                || changes.properties().stream().anyMatch(field -> !UPDATE_FIELDS.contains(field.getKey())
                || field.getValue().isNull())
                || changes.has("startDate") != changes.has("endDate")) {
            throw new InvalidTripRequestException();
        }
        for (String field : Set.of("tripName", "startDate", "endDate")) {
            if (changes.has(field) && !changes.get(field).isString()) {
                throw new InvalidTripRequestException();
            }
        }
        if (changes.has("regionCodes") && (!changes.get("regionCodes").isArray()
                || !StreamSupport.stream(changes.get("regionCodes").spliterator(), false).allMatch(JsonNode::isString))) {
            throw new InvalidTripRequestException();
        }
        try {
            TripUpdateRequest request = objectMapper.treeToValue(changes, TripUpdateRequest.class);
            if (!validator.validate(request).isEmpty()) {
                throw new InvalidTripRequestException();
            }
            return request;
        } catch (JacksonException exception) {
            throw new InvalidTripRequestException();
        }
    }

    private String fingerprint(JsonNode payload) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(canonicalize(payload));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(TripInternalErrorMessage.EDIT_COMMAND_HASH_UNAVAILABLE.message(), exception);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        if (value.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            value.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(field -> canonical.set(field.getKey(), canonicalize(field.getValue())));
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            value.forEach(element -> canonical.add(canonicalize(element)));
            return canonical;
        }
        return value;
    }
}
