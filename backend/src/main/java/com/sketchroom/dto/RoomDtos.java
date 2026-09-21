package com.sketchroom.dto;

import lombok.*;

public class RoomDtos {

    public record BoardSnapshot(String roomCode, java.util.List<DrawEvent> events,
                                long sequence, String sessionId, int connectedUsers) {}

    // ── What the frontend sends when joining a room ──────────────────
    // POST /api/rooms/join body: { "roomCode": "ABC123" }
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JoinRoomRequest {
        private String roomCode;
    }

    // ── What the backend returns after creating a room ───────────────
    // POST /api/rooms response: { "roomCode": "ABC123", "wsUrl": "..." }
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRoomResponse {
        private String roomCode;
        private String wsUrl;
        private String canvasSnapshot;
        private int connectedUsers;
    }

    // ── What the backend returns after joining a room ────────────────
    // POST /api/rooms/join response: includes existing canvas for late joiners
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class JoinRoomResponse {
        private String roomCode;
        private String wsUrl;
        private String canvasSnapshot;
        private int connectedUsers;
        private boolean success;
        private String message;
    }

    // ── What the backend returns when something goes wrong ───────────
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorResponse {
        private String error;
        private String message;
    }
}