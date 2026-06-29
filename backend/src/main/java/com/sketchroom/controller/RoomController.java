package com.sketchroom.controller;

import com.sketchroom.dto.RoomDtos.*;
import com.sketchroom.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@Slf4j
public class RoomController {

    private final RoomService roomService;

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
            @RequestBody JoinRoomRequest request) {

        log.info("Join room request for code: {}", request.getRoomCode());

        if (request.getRoomCode() == null
                || request.getRoomCode().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(
                        "INVALID_CODE",
                        "Room code cannot be empty"));
        }

        JoinRoomResponse response = roomService.joinRoom(
            request.getRoomCode().trim().toUpperCase());

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
    // Frontend polls this to update the user count badge
    @GetMapping("/{roomCode}/users")
    public ResponseEntity<Map<String, Integer>> getConnectedUsers(
            @PathVariable String roomCode) {

        int count = roomService.getConnectedUserCount(
            roomCode.toUpperCase());
        return ResponseEntity.ok(Map.of("connectedUsers", count));
    }

    // ── GET /api/rooms/health ─────────────────────────────────────────
    // Render.com pings this to check if the service is alive
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}