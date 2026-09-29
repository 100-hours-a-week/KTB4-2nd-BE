package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidTripDraftRequestException;
import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripDraftAlreadySubmittedException;
import com.yeodam.yeodambe.common.exception.TripDraftNotFoundException;
import com.yeodam.yeodambe.trip.entity.TripDraft;
import com.yeodam.yeodambe.trip.repository.TripDraftRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.request.TripDraftSaveRequest;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripDraftResponse;
import com.yeodam.yeodambe.trip.service.response.TripDraftSubmission;
import com.yeodam.yeodambe.user.exception.UserNotFoundException;
import com.yeodam.yeodambe.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TripDraftService {
    private final TripDraftRepository drafts;
    private final UserRepository users;
    private final RegionCatalog regions;
    private final ObjectMapper mapper;
    private final TripRepository trips;
    private final TripService tripService;
    private final Validator validator;

    @Transactional(readOnly = true)
    public TripDraftResponse find(Long userId) {
        return response(drafts.findByUserId(userId).orElseThrow(TripDraftNotFoundException::new));
    }

    @Transactional
    public TripDraftResponse save(Long userId, TripDraftSaveRequest request) {
        validate(request);
        users.findActiveByIdForUpdate(userId).orElseThrow(UserNotFoundException::new);
        String codes = mapper.writeValueAsString(request.regionCodes());
        TripDraft draft = drafts.findByUserIdForUpdate(userId).orElse(null);
        if (draft == null) {
            draft = drafts.saveAndFlush(new TripDraft(userId, request.tripName(), codes,
                    request.startDate(), request.endDate()));
        } else {
            if (draft.getSubmittedTripId() != null) throw new TripDraftAlreadySubmittedException();
            if (draft.replace(request.tripName(), codes, request.startDate(), request.endDate())) drafts.flush();
        }
        return response(draft);
    }

    @Transactional
    public void delete(Long userId) {
        users.findActiveByIdForUpdate(userId).orElseThrow(UserNotFoundException::new);
        drafts.findByUserIdForUpdate(userId).ifPresent(draft -> {
            if (draft.getSubmittedTripId() != null) throw new TripDraftAlreadySubmittedException();
            drafts.delete(draft);
        });
    }

    @Transactional
    public TripDraftSubmission submit(Long userId, Long draftId) {
        users.findActiveByIdForUpdate(userId).orElseThrow(UserNotFoundException::new);
        TripDraft draft = drafts.findByIdAndUserIdForUpdate(draftId, userId)
                .orElseThrow(TripDraftNotFoundException::new);
        if (draft.getSubmittedTripId() != null) {
            var trip = trips.findByIdAndUserIdAndDeletedAtIsNull(draft.getSubmittedTripId(), userId)
                    .orElseThrow(TripNotFoundException::new);
            return new TripDraftSubmission(new TripCreateResponse(trip.getId(), trip.getProcessingStatus()), false);
        }
        TripCreateRequest request = new TripCreateRequest(draft.getTripName(), draft.getStartDate(),
                draft.getEndDate(), response(draft).regionCodes());
        if (!validator.validate(request).isEmpty()) throw new InvalidTripRequestException();
        TripCreateResponse created = tripService.createTrip(userId, request);
        draft.attach(created.tripId());
        return new TripDraftSubmission(created, true);
    }

    private void validate(TripDraftSaveRequest request) {
        if (request == null || request.regionCodes() == null || request.regionCodes().size() > 10
                || new HashSet<>(request.regionCodes()).size() != request.regionCodes().size()
                || (request.startDate() == null) != (request.endDate() == null)) {
            throw new InvalidTripDraftRequestException();
        }
        String name = request.tripName();
        if (name != null && (name.isBlank() || name.codePointCount(0, name.length()) > 10)) {
            throw new InvalidTripDraftRequestException();
        }
        for (String code : request.regionCodes()) {
            try {
                if (code == null) throw new InvalidTripDraftRequestException();
                regions.getRequired(code);
            } catch (InvalidTripRequestException e) {
                throw new InvalidTripDraftRequestException();
            }
        }
        if (request.startDate() != null && (request.endDate().isBefore(request.startDate())
                || request.startDate().isAfter(LocalDate.now()) || request.endDate().isAfter(LocalDate.now())
                || ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1 > 92)) {
            throw new InvalidTripDraftRequestException();
        }
    }

    private TripDraftResponse response(TripDraft draft) {
        JsonNode codes = mapper.readTree(draft.getRegionCodesJson());
        List<String> regionCodes = new ArrayList<>();
        for (JsonNode code : codes) regionCodes.add(code.asText());
        return new TripDraftResponse(draft.getId(), draft.getTripName(), regionCodes,
                draft.getStartDate(), draft.getEndDate(), draft.getSubmittedTripId(), draft.getUpdatedAt());
    }
}
