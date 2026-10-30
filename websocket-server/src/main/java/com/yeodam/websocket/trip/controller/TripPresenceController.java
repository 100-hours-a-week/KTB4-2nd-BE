package com.yeodam.websocket.trip.controller;

import com.yeodam.websocket.trip.service.TripPresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class TripPresenceController {
    private final TripPresenceService service;

    @MessageMapping("/trips/{tripId}/presence/join")
    public void join(@DestinationVariable String tripId, Message<?> message) {
        service.join(tripId, message);
    }

    @MessageMapping("/trips/{tripId}/presence/heartbeat")
    public void heartbeat(@DestinationVariable String tripId, Message<?> message) {
        service.heartbeat(tripId, message);
    }

    @MessageMapping("/trips/{tripId}/presence/leave")
    public void leave(@DestinationVariable String tripId, Message<?> message) {
        service.leave(tripId, message);
    }

    @MessageExceptionHandler(RuntimeException.class)
    public void handlePresenceFailure(RuntimeException failure, Message<?> message) {
        service.rejectCommand(message, failure);
    }
}
