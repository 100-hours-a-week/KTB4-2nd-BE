package com.yeodam.yeodambe.trip.performance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class LocalAnalysisStubTest {
    private final HttpClient http = HttpClient.newHttpClient();
    @Test void readinessDeadlineExpiresAndCanBeReset() throws Exception {
        try (var stub = new LocalAnalysisStub()) {
            var request = HttpRequest.newBuilder(stub.aiBaseUrl().resolve("/health")).GET().build();
            stub.setReadyDelay(200);
            assertFalse(new ObjectMapper().readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body()).path("model_loaded").asBoolean());
            Thread.sleep(250);
            assertTrue(new ObjectMapper().readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body()).path("model_loaded").asBoolean());
            stub.setReadyDelay(10000); stub.setReadyDelay(0);
            assertTrue(new ObjectMapper().readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body()).path("model_loaded").asBoolean());
            assertThrows(IllegalArgumentException.class, () -> stub.setReadyDelay(-1));
        }
    }
    @Test void reportsReceiptOnceWithoutRequestBody() throws Exception {
        try (var stub = new LocalAnalysisStub()) {
            var receipts = new java.util.concurrent.CopyOnWriteArrayList<LocalAnalysisStub.AnalysisReceipt>();
            stub.onAnalysisReceived(receipts::add);
            stub.register(7, new LocalAnalysisStub.RunSpec(1,1,LocalAnalysisStub.FaultMode.NONE,0,0,0,false));
            long before = System.nanoTime();
            var response = http.send(HttpRequest.newBuilder(stub.aiBaseUrl().resolve("/trips/7/process"))
                .header("X-Request-ID","receipt")
                .POST(HttpRequest.BodyPublishers.ofString("{\"execution_id\":\"exec\",\"secret\":\"hidden\",\"attachments\":[{\"trip_attachment_id\":11}]}" )).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode()); assertEquals(1,receipts.size());
            var receipt = receipts.getFirst();
            assertEquals(7,receipt.tripId()); assertEquals("exec",receipt.executionId()); assertEquals("receipt",receipt.requestId());
            assertTrue(receipt.receivedNanos() >= before); assertFalse(receipt.toString().contains("hidden"));
        }
    }
    @Test void dynamicResultUsesRequestIds() throws Exception {
        try (var stub = new LocalAnalysisStub()) {
            stub.register(7, new LocalAnalysisStub.RunSpec(2, 1, LocalAnalysisStub.FaultMode.NONE, 0, 0, 0, false));
            var request = HttpRequest.newBuilder(stub.aiBaseUrl().resolve("/trips/7/process"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"execution_id\":\"run\",\"attachments\":[{\"trip_attachment_id\":11},{\"trip_attachment_id\":12}]}" )).build();
            var body = new ObjectMapper().readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("run", body.path("execution_id").asString());
            assertEquals(11, body.path("result").path("places").get(0).path("representative_attachment_id").asLong());
            assertEquals(2, body.path("result").path("places").size());
            assertEquals(body.path("result").path("places").get(0).path("latitude"), body.path("result").path("places").get(1).path("latitude"));
        }
    }
    @Test void barrierTimeoutReleasesWaiters() throws Exception {
        try (var stub = new LocalAnalysisStub()) {
            stub.register(9, new LocalAnalysisStub.RunSpec(1, 1, LocalAnalysisStub.FaultMode.NONE, 0, 0, 0, true));
            var pending = http.sendAsync(HttpRequest.newBuilder(stub.aiBaseUrl().resolve("/trips/9/process"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"execution_id\":\"r\",\"attachments\":[{\"trip_attachment_id\":1}]}" )).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(stub.awaitAnalysisRequests(Set.of(9L), Duration.ofSeconds(2)));
            assertFalse(pending.isDone());
            assertFalse(stub.awaitAnalysisRequests(Set.of(10L), Duration.ofMillis(10)));
            stub.releaseAnalysis(Set.of(9L));
            assertEquals(200, pending.get(2, java.util.concurrent.TimeUnit.SECONDS).statusCode());
        }
    }
    @Test void differentTripsDoNotShareAttemptCounters() throws Exception {
        try (var stub = new LocalAnalysisStub()) {
            for (long id : new long[]{1, 2}) {
                stub.register(id, new LocalAnalysisStub.RunSpec(20, 20, LocalAnalysisStub.FaultMode.FIRST_429, 0, 0, 0, false));
                var coordinate = stub.coordinate(id, 0);
                var url = stub.kakaoBaseUrl().resolve("/v2/local/geo/coord2address.json?x="+coordinate.longitude()+"&y="+coordinate.latitude());
                assertEquals(429, http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(200, http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(2, stub.snapshot(id).attempts());
                assertEquals(1, stub.snapshot(id).retries());
            }
        }
    }
}
