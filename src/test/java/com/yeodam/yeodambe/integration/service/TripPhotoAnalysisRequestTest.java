package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TripPhotoAnalysisRequestTest {
    @Test
    void AI_요청의_필드명이_내부_계약과_일치한다() {
        var request = new TripPhotoAnalysisRequest("run-1", "여행",
                new TripPhotoAnalysisRequest.Period(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 21)),
                List.of(), List.of(new TripPhotoAnalysisRequest.Photo(1L, "analyze", null, null, null, null)));
        String body = new ObjectMapper().writeValueAsString(request);
        assertTrue(body.contains("\"trip_name\""));
        assertTrue(body.contains("\"execution_id\":\"run-1\""));
        assertTrue(body.contains("\"start_date\""));
        assertTrue(body.contains("\"trip_attachment_id\""));
        assertTrue(body.contains("\"analyze_storage_key\""));
    }
}
