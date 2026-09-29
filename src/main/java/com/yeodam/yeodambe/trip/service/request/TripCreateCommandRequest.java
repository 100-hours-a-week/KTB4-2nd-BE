package com.yeodam.yeodambe.trip.service.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

public class TripCreateCommandRequest {
    private JsonNode draftId;
    private boolean draftIdPresent;
    private boolean directFieldPresent;
    private boolean unknownFieldPresent;
    private String tripName;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<String> regionCodes;

    public void setDraftId(JsonNode draftId) {
        this.draftId = draftId;
        this.draftIdPresent = true;
    }

    public void setTripName(String tripName) {
        this.tripName = tripName;
        this.directFieldPresent = true;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
        this.directFieldPresent = true;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
        this.directFieldPresent = true;
    }

    public void setRegionCodes(List<String> regionCodes) {
        this.regionCodes = regionCodes;
        this.directFieldPresent = true;
    }

    @JsonAnySetter
    public void unknownField(String name, JsonNode value) {
        this.unknownFieldPresent = true;
    }

    public boolean isDraftRequest() {
        return draftIdPresent;
    }

    public boolean hasUnknownFields() {
        return unknownFieldPresent;
    }

    public long requiredDraftId() {
        if (directFieldPresent || unknownFieldPresent || draftId == null || !draftId.isIntegralNumber()
                || !draftId.canConvertToLong() || draftId.asLong() <= 0) {
            throw new InvalidTripRequestException();
        }
        return draftId.asLong();
    }

    public TripCreateRequest directRequest() {
        return new TripCreateRequest(tripName, startDate, endDate, regionCodes);
    }
}
