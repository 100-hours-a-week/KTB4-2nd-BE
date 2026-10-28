package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TripShareCreateRequest(
        @NotNull @Positive Long granteeUserId,
        @NotNull TripSharePermission permission
) {
}
