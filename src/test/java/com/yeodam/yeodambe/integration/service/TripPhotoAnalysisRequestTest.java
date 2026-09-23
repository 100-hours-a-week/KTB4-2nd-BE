package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
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
        ObjectMapper json = new ObjectMapper();
        JsonNode actual = json.readTree(json.writeValueAsString(request));
        JsonNode expected = json.readTree("""
                {
                  "execution_id":"run-1",
                  "trip_name":"여행",
                  "period":{"start_date":"2026-09-20","end_date":"2026-09-21"},
                  "regions":[],
                  "attachments":[{
                    "trip_attachment_id":1,
                    "analyze_storage_key":"analyze",
                    "taken_at":null,
                    "latitude":null,
                    "longitude":null,
                    "device_model":null
                  }]
                }
                """);

        assertEquals(expected, actual);
    }
}
