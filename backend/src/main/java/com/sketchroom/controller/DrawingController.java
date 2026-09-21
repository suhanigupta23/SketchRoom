package com.sketchroom.controller;

import com.sketchroom.dto.DrawEvent;
import com.sketchroom.dto.RoomDtos.BoardSnapshot;
import com.sketchroom.dto.RoomDtos.ErrorResponse;
import com.sketchroom.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.NoSuchElementException;

@Controller
@RequiredArgsConstructor
@Slf4j
public class DrawingController {
    private final RoomService roomService;

    @MessageMapping("/draw/{roomKey}")
    public void handleDrawEvent(@DestinationVariable String roomKey, @Payload DrawEvent event,
                                @Header("simpSessionId") String sessionId, SimpMessageHeaderAccessor headers) {
        requireMembership(roomKey, headers);
        roomService.applyEvent(roomKey, sessionId, event);
    }

    @MessageMapping("/clear/{roomKey}")
    public void handleClearEvent(@DestinationVariable String roomKey, @Header("simpSessionId") String sessionId,
                                 SimpMessageHeaderAccessor headers) {
        requireMembership(roomKey, headers);
        roomService.applyEvent(roomKey, sessionId, DrawEvent.builder().type("clear").build());
    }

    // Returning a private snapshot closes the gap between subscribing and loading history.
    @MessageMapping("/join/{roomKey}")
    @SendToUser(value = "/queue/snapshot", broadcast = false)
    public BoardSnapshot handleUserJoin(@DestinationVariable String roomKey,
                                        @Header("simpSessionId") String sessionId,
                                        SimpMessageHeaderAccessor headers) {
        String code = roomService.normalizeRoomCode(roomKey);
        Map<String, Object> attrs = headers.getSessionAttributes();
        if (attrs == null) throw new IllegalArgumentException("Missing WebSocket session.");
        String previous = (String) attrs.get("roomKey");
        if (previous != null && !previous.equals(code)) throw new IllegalArgumentException("Reconnect before changing rooms.");
        BoardSnapshot snapshot = roomService.joinSession(code, sessionId);
        attrs.put("roomKey", code);
        return snapshot;
    }

    private void requireMembership(String roomKey, SimpMessageHeaderAccessor headers) {
        Map<String, Object> attrs = headers.getSessionAttributes();
        if (attrs == null || !roomService.normalizeRoomCode(roomKey).equals(attrs.get("roomKey"))) {
            throw new IllegalArgumentException("Join this room before drawing.");
        }
    }

    @MessageExceptionHandler
    @SendToUser(value = "/queue/errors", broadcast = false)
    public ErrorResponse handleError(Exception error) {
        if (error instanceof NoSuchElementException) return new ErrorResponse("ROOM_NOT_FOUND", error.getMessage());
        if (error instanceof IllegalArgumentException) return new ErrorResponse("INVALID_EVENT", error.getMessage());
        log.error("Room message failed", error);
        return new ErrorResponse("SERVICE_UNAVAILABLE", "The board could not be saved. Reconnecting to restore the shared state.");
    }
}
