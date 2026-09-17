package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PlaceSearchRequest(
        @NotBlank(message = "1자 이상 입력해야 합니다.")
        @Pattern(regexp = "^[가-힣]+$", message = "한글만 입력할 수 있습니다.")
        String query,

        @Min(1)
        Integer pageNo
) {
    public int requestPageNo() {
        return pageNo == null ? 1 : pageNo;
    }
}
