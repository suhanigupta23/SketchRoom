package com.sketchroom.controller;

import com.sketchroom.dto.DrawEvent;
import com.sketchroom.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@Slf4j
public class DrawingController {

    private final SimpMessagingTemplate messagingTemplate;
    private final RoomService roomService;

    // ── Receive draw events from any user, broadcast to whole room ────
    // Frontend sends to: /app/draw/ABC123
    // This method broadcasts to: /topic/room/ABC123
    @MessageMapping("/draw/{roomKey}")
    public void handleDrawEvent(
            @DestinationVariable String roomKey,
            @Payload DrawEvent event,
            @Header("simpSessionId") String sessionId) {

        log.debug("Draw event in room {} from session {}: type={}",
            roomKey, sessionId, event.getType());

        // Broadcast to ALL subscribers immediately for near-zero latency
        messagingTemplate.convertAndSend(
            "/topic/room/" + roomKey, event);

        // Buffer event in Redis asynchronously for canvas snapshot
        roomService.appendDrawEvent(roomKey, event);
    }

    // ── Receive clear event, broadcast to whole room ──────────────────
    // Frontend sends to: /app/clear/ABC123
    @MessageMapping("/clear/{roomKey}")
    public void handleClearEvent(
            @DestinationVariable String roomKey,
            @Header("simpSessionId") String sessionId) {

        log.info("Canvas cleared in room {} by session {}",
            roomKey, sessionId);

        DrawEvent clearEvent = DrawEvent.builder()
            .type("clear")
            .build();

        // Wipes Redis buffer for this room
        roomService.appendDrawEvent(roomKey, clearEvent);

        // Broadcast clear to everyone in the room
        messagingTemplate.convertAndSend(
            "/topic/room/" + roomKey, clearEvent);
    }

    // ── User joins room via WebSocket ─────────────────────────────────
    // Frontend sends to: /app/join/ABC123
    // Backend updates Redis membership and broadcasts new user count
    @MessageMapping("/join/{roomKey}")
    public void handleUserJoin(
            @DestinationVariable String roomKey,
            @Header("simpSessionId") String sessionId,
            SimpMessageHeaderAccessor headerAccessor)
        {
        
        // Store roomKey in session so disconnect handler can read it
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put("roomKey", roomKey);
        }

        roomService.addUserToRoom(roomKey, sessionId);
        int count = roomService.getConnectedUserCount(roomKey);

        log.info("User {} joined room {}. Total: {}",
            sessionId, roomKey, count);

        // Broadcast updated count to everyone in the room
        // Frontend's connectedUsers state updates automatically
        messagingTemplate.convertAndSend(
            "/topic/room/" + roomKey + "/users",
            Map.of("connectedUsers", count));
    }
}