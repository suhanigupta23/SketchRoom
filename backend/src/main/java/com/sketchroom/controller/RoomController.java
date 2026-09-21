package com.sketchroom.controller;

import com.sketchroom.dto.RoomDtos.*;
import com.sketchroom.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@Slf4j
public class RoomController {

    private final RoomService roomService;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<RedisConnectionFactory> redis;

    // ── POST /api/rooms ───────────────────────────────────────────────
    // Called when user clicks "Create a Room" on the landing page
    @PostMapping
    public ResponseEntity<CreateRoomResponse> createRoom() {
        log.info("Create room request received");
        CreateRoomResponse response = roomService.createRoom();
        return ResponseEntity.ok(response);
    }

    // ── POST /api/rooms/join ──────────────────────────────────────────
    // Called when user enters a code and clicks "Join"
    // Request body: { "roomCode": "ABC123" }
    @PostMapping("/join")
    public ResponseEntity<?> joinRoom(
            @RequestBody JoinRoomRequest request,
            @RequestParam(defaultValue = "true") boolean includeSnapshot) {

        log.info("Join room request for code: {}", request.getRoomCode());

        if (request.getRoomCode() == null
                || request.getRoomCode().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(
                        "INVALID_CODE",
                        "Room code cannot be empty"));
        }

        JoinRoomResponse response = roomService.joinRoom(
            request.getRoomCode(), includeSnapshot);

        if (!response.isSuccess()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(
                        "ROOM_NOT_FOUND",
                        response.getMessage()));
        }

        return ResponseEntity.ok(response);
    }

    // ── GET /api/rooms/{roomCode}/users ───────────────────────────────
    // Returns current connected user count for a room
    // Optional HTTP count endpoint; the frontend receives live counts over STOMP.
    @GetMapping("/{roomCode}/users")
    public ResponseEntity<Map<String, Integer>> getConnectedUsers(
            @PathVariable String roomCode) {

        int count = roomService.getConnectedUserCount(
            roomCode.toUpperCase());
        return ResponseEntity.ok(Map.of("connectedUsers", count));
    }

    // Readiness is separate from liveness so operators can detect storage outages.
    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbc.getObject().queryForObject("SELECT 1", Integer.class);
            try (var connection = redis.getObject().getConnection()) {
                if (!"PONG".equals(connection.ping())) throw new IllegalStateException("Redis did not respond");
            }
            return ResponseEntity.ok(Map.of("status", "ready"));
        } catch (Exception error) {
            return ResponseEntity.status(503).body(Map.of("status", "unavailable"));
        }
    }

    // ── GET /api/rooms/health ─────────────────────────────────────────
    // Render.com pings this to check if the service is alive
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}