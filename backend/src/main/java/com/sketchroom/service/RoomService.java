package com.sketchroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sketchroom.dto.DrawEvent;
import com.sketchroom.dto.RoomDtos.*;
import com.sketchroom.model.Room;
import com.sketchroom.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoomService {

    private final RoomRepository roomRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.websocket.url:ws://localhost:8080/ws}")
    private String wsBaseUrl;

    // Redis key patterns
    private static final String ROOM_MEMBERS_KEY = "room:members:";
    private static final String ROOM_EVENTS_KEY  = "room:events:";

    // Room key generation settings
    private static final String KEY_CHARS =
        "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int KEY_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    // ── Create Room ───────────────────────────────────────────────────

    public CreateRoomResponse createRoom() {
        String roomKey = generateUniqueKey();

        Room room = Room.builder()
                .roomKey(roomKey)
                .build();
        roomRepository.save(room);

        // Initialize empty members set in Redis with 24h TTL
        String membersKey = ROOM_MEMBERS_KEY + roomKey;
        redisTemplate.opsForSet().add(membersKey, "placeholder");
        redisTemplate.opsForSet().remove(membersKey, "placeholder");
        redisTemplate.expire(membersKey, 24, TimeUnit.HOURS);

        log.info("Created room: {}", roomKey);

        return CreateRoomResponse.builder()
                .roomCode(roomKey)
                .wsUrl(wsBaseUrl)
                .canvasSnapshot(null)
                .connectedUsers(0)
                .build();
    }

    // ── Join Room ─────────────────────────────────────────────────────

    public JoinRoomResponse joinRoom(String roomCode) {
        Optional<Room> roomOpt =
            roomRepository.findByRoomKeyAndActiveTrue(
                roomCode.toUpperCase());

        if (roomOpt.isEmpty()) {
            return JoinRoomResponse.builder()
                    .success(false)
                    .message("Room not found or expired.")
                    .build();
        }

        Room room = roomOpt.get();
        int connectedUsers = getConnectedUserCount(roomCode);

        log.info("User joining room: {} ({} users currently)",
            roomCode, connectedUsers);

        return JoinRoomResponse.builder()
                .roomCode(room.getRoomKey())
                .wsUrl(wsBaseUrl)
                .canvasSnapshot(room.getCanvasSnapshot())
                .connectedUsers(connectedUsers)
                .success(true)
                .message("Joined successfully")
                .build();
    }

    // ── Session Tracking ──────────────────────────────────────────────

    public void addUserToRoom(String roomKey, String sessionId) {
        String key = ROOM_MEMBERS_KEY + roomKey;
        redisTemplate.opsForSet().add(key, sessionId);
        redisTemplate.expire(key, 24, TimeUnit.HOURS);
        log.info("Session {} joined room {}", sessionId, roomKey);
    }

    public void removeUserFromRoom(String roomKey, String sessionId) {
        String key = ROOM_MEMBERS_KEY + roomKey;
        redisTemplate.opsForSet().remove(key, sessionId);
        log.info("Session {} left room {}", sessionId, roomKey);
    }

    public int getConnectedUserCount(String roomKey) {
        Long count = redisTemplate.opsForSet()
            .size(ROOM_MEMBERS_KEY + roomKey);
        return count != null ? count.intValue() : 0;
    }

    // ── Draw Event Buffering ──────────────────────────────────────────

    public void appendDrawEvent(String roomKey, DrawEvent event) {
        try {
            // If it's a clear event, wipe the entire buffer
            // No point keeping old draw events after a clear
            if ("clear".equals(event.getType())) {
                redisTemplate.delete(ROOM_EVENTS_KEY + roomKey);
                return;
            }

            String eventsKey = ROOM_EVENTS_KEY + roomKey;
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(eventsKey, json);

            // Keep only the last 2000 events per room
            redisTemplate.opsForList().trim(eventsKey, -2000, -1);
            redisTemplate.expire(eventsKey, 24, TimeUnit.HOURS);

        } catch (Exception e) {
            log.warn("Failed to buffer draw event for room {}: {}",
                roomKey, e.getMessage());
        }
    }

    // ── Periodic Snapshot Save (every 30 seconds) ─────────────────────

    @Scheduled(fixedDelay = 30_000)
    public void saveCanvasSnapshots() {
        Set<String> keys = redisTemplate.keys(ROOM_EVENTS_KEY + "*");
        if (keys == null || keys.isEmpty()) return;

        for (String eventsKey : keys) {
            String roomKey = eventsKey.replace(ROOM_EVENTS_KEY, "");
            try {
                List<String> events =
                    redisTemplate.opsForList().range(eventsKey, 0, -1);
                if (events == null || events.isEmpty()) continue;

                String snapshot = "[" + String.join(",", events) + "]";

                roomRepository
                    .findByRoomKeyAndActiveTrue(roomKey)
                    .ifPresent(room -> {
                        room.setCanvasSnapshot(snapshot);
                        room.setSnapshotUpdatedAt(LocalDateTime.now());
                        roomRepository.save(room);
                        log.info("Saved snapshot for room {} ({} events)",
                            roomKey, events.size());
                    });

            } catch (Exception e) {
                log.error("Snapshot save failed for room {}: {}",
                    roomKey, e.getMessage());
            }
        }
    }

    // ── Cleanup Expired Rooms (every hour) ────────────────────────────

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupExpiredRooms() {
        int count = roomRepository
            .deactivateExpiredRooms(LocalDateTime.now());
        if (count > 0) {
            log.info("Deactivated {} expired rooms", count);
        }
    }

    // ── Room Key Generation ───────────────────────────────────────────

    private String generateUniqueKey() {
        String key;
        int attempts = 0;
        do {
            key = generateKey();
            attempts++;
            if (attempts > 100) {
                throw new RuntimeException(
                    "Could not generate unique room key after 100 attempts");
            }
        } while (roomRepository.existsByRoomKey(key));
        return key;
    }

    private String generateKey() {
        StringBuilder sb = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            sb.append(KEY_CHARS.charAt(
                RANDOM.nextInt(KEY_CHARS.length())));
        }
        return sb.toString();
    }
}