package com.sketchroom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sketchroom.dto.DrawEvent;
import com.sketchroom.dto.RoomDtos.*;
import com.sketchroom.model.Room;
import com.sketchroom.repository.RoomRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Single-instance room coordinator. All operations for a room use the same lock. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoomService {
    private final RoomRepository roomRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${app.websocket.url:}")
    private String wsBaseUrl;
    @Value("${app.board.max-events:50000}")
    private int maxEvents = 50_000;

    private static final String MEMBERS = "room:members:";
    private static final String EVENTS = "room:events:";
    private static final String KEY_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Board> boards = new ConcurrentHashMap<>();
    private final Object[] locks = java.util.stream.IntStream.range(0, 128)
            .mapToObj(i -> new Object()).toArray();

    private static class Board {
        final Room room;
        final List<DrawEvent> events = new ArrayList<>();
        final Set<String> eventIds = new HashSet<>();
        final Map<String, String> strokeOwners = new HashMap<>();
        final Set<String> sessions = new HashSet<>();
        long sequence;
        long nextEventExpiryRefresh;
        boolean dirty;
        Board(Room room) { this.room = room; }
    }

    public String normalizeRoomCode(String code) {
        if (code == null || !code.trim().matches("(?i)[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("Enter a six-character room code.");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private Object lock(String code) {
        return locks[Math.floorMod(code.hashCode(), locks.length)];
    }

    public CreateRoomResponse createRoom() {
        // A unique database constraint remains the final guard against collisions.
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 6; i++) code.append(KEY_CHARS.charAt(RANDOM.nextInt(KEY_CHARS.length())));
            if (roomRepository.existsByRoomKey(code.toString())) continue;
            try {
                Room room = roomRepository.saveAndFlush(Room.builder().roomKey(code.toString()).build());
                return CreateRoomResponse.builder().roomCode(room.getRoomKey()).wsUrl(wsBaseUrl)
                        .canvasSnapshot(null).connectedUsers(0).build();
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (!roomRepository.existsByRoomKey(code.toString())) throw e;
            }
        }
        throw new IllegalStateException("Could not allocate a room code. Please try again.");
    }

    public void validateRoom(String input) {
        String code = normalizeRoomCode(input);
        synchronized (lock(code)) { board(code); }
    }

    public JoinRoomResponse joinRoom(String input) { return joinRoom(input, true); }

    public JoinRoomResponse joinRoom(String input, boolean includeSnapshot) {
        String code = normalizeRoomCode(input);
        synchronized (lock(code)) {
            Board board;
            try { board = board(code); }
            catch (NoSuchElementException e) {
                return JoinRoomResponse.builder().success(false).message(e.getMessage()).build();
            }
            return JoinRoomResponse.builder().roomCode(code).wsUrl(wsBaseUrl)
                    .canvasSnapshot(includeSnapshot ? json(board.events) : null).connectedUsers(board.sessions.size())
                    .success(true).message("Joined successfully").build();
        }
    }

    /** Called after subscriptions exist, on both the first connection and reconnects. */
    public BoardSnapshot joinSession(String input, String sessionId) {
        String code = normalizeRoomCode(input);
        synchronized (lock(code)) {
            Board board = board(code);
            redisTemplate.opsForSet().add(MEMBERS + code, sessionId);
            redisTemplate.expire(MEMBERS + code, 24, TimeUnit.HOURS);
            board.sessions.add(sessionId);
            publishCount(code, board);
            return new BoardSnapshot(code, List.copyOf(board.events), board.sequence,
                    sessionId, board.sessions.size());
        }
    }

    public void removeUserFromRoom(String code, String sessionId) {
        synchronized (lock(code)) {
            Board board = boards.get(code);
            if (board == null || !board.sessions.remove(sessionId)) return;
            try { redisTemplate.opsForSet().remove(MEMBERS + code, sessionId); }
            catch (RuntimeException e) { log.warn("Presence cleanup failed for {}", code, e); }
            publishCount(code, board);
            // Save before releasing the last in-memory copy. Retry via the scheduled job on failure.
            if (board.sessions.isEmpty()) {
                try {
                    saveSnapshot(board);
                    boards.remove(code);
                } catch (RuntimeException e) { log.warn("Final snapshot failed for {}", code, e); }
            }
        }
    }

    public int getConnectedUserCount(String input) {
        String code = normalizeRoomCode(input);
        synchronized (lock(code)) {
            Board board = boards.get(code);
            return board == null ? 0 : board.sessions.size();
        }
    }

    public void applyEvent(String input, String sessionId, DrawEvent event) {
        String code = normalizeRoomCode(input);
        synchronized (lock(code)) {
            Board board = board(code);
            if (!board.sessions.contains(sessionId)) throw new IllegalArgumentException("Join this room before drawing.");
            validate(event);
            if (event.getEventId() == null) event.setEventId(UUID.randomUUID().toString()); // Older clients.
            if (board.eventIds.contains(event.getEventId())) return;
            if (!"clear".equals(event.getType()) && board.events.size() >= maxEvents) {
                throw new IllegalArgumentException("This board is full. Create a new room or clear the board to continue.");
            }
            if ("draw".equals(event.getType()) && event.getStrokeId() == null) {
                event.setStrokeId(UUID.randomUUID().toString());
            }
            if ("undo".equals(event.getType()) || "redo".equals(event.getType())) {
                if (!sessionId.equals(board.strokeOwners.get(event.getStrokeId()))) {
                    throw new IllegalArgumentException("Only your strokes from this connection can be undone.");
                }
            } else if ("draw".equals(event.getType())) {
                String owner = board.strokeOwners.get(event.getStrokeId());
                if (owner != null && !sessionId.equals(owner)) throw new IllegalArgumentException("Stroke belongs to another connection.");
            }
            event.setAuthorId(sessionId);
            event.setSequence(board.sequence + 1);
            // Persist before broadcasting. Failed writes never silently become accepted drawing.
            redisTemplate.opsForList().rightPush(EVENTS + code, json(event));
            // The append is the commit point; a TTL refresh failure must not roll back the sequence.
            refreshEventExpiry(code, board);
            board.sequence = event.getSequence();
            if ("clear".equals(event.getType())) {
                board.events.clear();
                board.eventIds.clear();
                board.strokeOwners.clear();
            }
            board.events.add(event);
            board.eventIds.add(event.getEventId());
            if ("draw".equals(event.getType())) board.strokeOwners.put(event.getStrokeId(), sessionId);
            board.dirty = true;
            if ("clear".equals(event.getType())) {
                // Keep the clear marker in Redis even if PostgreSQL is temporarily unavailable.
                try {
                    saveSnapshot(board);
                    redisTemplate.opsForList().trim(EVENTS + code, -1, -1);
                } catch (RuntimeException e) { log.warn("Clear checkpoint will be retried for {}", code, e); }
            }
            messagingTemplate.convertAndSend("/topic/room/" + code, event);
        }
    }

    /** One Redis round trip for a short group of segments; broadcast only after commit. */
    public void applyDrawBatch(String input, String sessionId, List<DrawEvent> incoming) {
        if (incoming == null || incoming.isEmpty() || incoming.size() > 32) {
            throw new IllegalArgumentException("A drawing batch must contain 1 to 32 segments.");
        }
        String code = normalizeRoomCode(input);
        synchronized (lock(code)) {
            Board board = board(code);
            if (!board.sessions.contains(sessionId)) throw new IllegalArgumentException("Join this room before drawing.");
            List<DrawEvent> accepted = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (DrawEvent event : incoming) {
                validate(event);
                if (!"draw".equals(event.getType())) throw new IllegalArgumentException("Only drawing segments may be batched.");
                if (event.getEventId() == null) event.setEventId(UUID.randomUUID().toString());
                if (board.eventIds.contains(event.getEventId()) || !ids.add(event.getEventId())) continue;
                if (event.getStrokeId() == null) event.setStrokeId(UUID.randomUUID().toString());
                String owner = board.strokeOwners.get(event.getStrokeId());
                if (owner != null && !sessionId.equals(owner)) throw new IllegalArgumentException("Stroke belongs to another connection.");
                event.setAuthorId(sessionId);
                event.setSequence(board.sequence + accepted.size() + 1);
                accepted.add(event);
            }
            if (accepted.isEmpty()) return;
            if (board.events.size() + accepted.size() > maxEvents) throw new IllegalArgumentException("This board is full. Create a new room or clear the board to continue.");
            redisTemplate.opsForList().rightPushAll(EVENTS + code, accepted.stream().map(this::json).toList());
            refreshEventExpiry(code, board);
            for (DrawEvent event : accepted) {
                board.events.add(event);
                board.eventIds.add(event.getEventId());
                board.strokeOwners.put(event.getStrokeId(), sessionId);
                board.sequence = event.getSequence();
            }
            board.dirty = true;
            for (DrawEvent event : accepted) messagingTemplate.convertAndSend("/topic/room/" + code, event);
        }
    }

    private void refreshEventExpiry(String code, Board board) {
        // Refresh once per minute, not for every pointer segment. Each synchronous
        // Redis request adds network latency while this room's drawing queue waits.
        long now = System.nanoTime();
        if (board.nextEventExpiryRefresh == 0 || now - board.nextEventExpiryRefresh >= 0) {
            try {
                if (Boolean.TRUE.equals(redisTemplate.expire(EVENTS + code, 24, TimeUnit.HOURS))) {
                    board.nextEventExpiryRefresh = now + TimeUnit.MINUTES.toNanos(1);
                }
            } catch (RuntimeException e) { log.warn("Event TTL refresh failed for {}", code, e); }
        }
    }

    private void validate(DrawEvent event) {
        if (event == null || event.getType() == null || !Set.of("draw", "clear", "undo", "redo").contains(event.getType())) {
            throw new IllegalArgumentException("Unsupported drawing command.");
        }
        if (event.getEventId() != null && !event.getEventId().matches("[A-Za-z0-9-]{1,80}")) {
            throw new IllegalArgumentException("Invalid event identifier.");
        }
        if (event.getStrokeId() != null && !event.getStrokeId().matches("[A-Za-z0-9-]{1,80}")) {
            throw new IllegalArgumentException("Invalid stroke identifier.");
        }
        if (Set.of("undo", "redo").contains(event.getType()) && event.getStrokeId() == null) {
            throw new IllegalArgumentException("A stroke identifier is required.");
        }
        if ("draw".equals(event.getType()) && (!coordinate(event.getX(), 3000) || !coordinate(event.getPrevX(), 3000)
                || !coordinate(event.getY(), 2000) || !coordinate(event.getPrevY(), 2000)
                || event.getSize() == null || event.getSize() < 1 || event.getSize() > 20
                || event.getColor() == null || !event.getColor().matches("#[0-9a-fA-F]{6}")
                || event.getIsEraser() == null)) {
            throw new IllegalArgumentException("Invalid coordinates, color, or brush size.");
        }
    }

    private boolean coordinate(Double value, int max) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= max;
    }

    private Board board(String code) {
        Board existing = boards.get(code);
        if (existing != null) {
            requireActive(existing.room);
            return existing;
        }
        Room room = roomRepository.findByRoomKeyAndActiveTrue(code)
                .orElseThrow(() -> new NoSuchElementException("Room not found or expired."));
        requireActive(room);
        Board board = new Board(room);
        List<String> live = redisTemplate.opsForList().range(EVENTS + code, 0, -1);
        try {
            List<DrawEvent> history = live != null && !live.isEmpty()
                    ? objectMapper.readValue("[" + String.join(",", live) + "]", new TypeReference<>() {})
                    : room.getCanvasSnapshot() == null ? List.of()
                    : objectMapper.readValue(room.getCanvasSnapshot(), new TypeReference<>() {});
            for (DrawEvent event : history) {
                // Upgrade existing snapshots without changing the database schema.
                board.sequence = Math.max(board.sequence + 1, event.getSequence() == null ? 0 : event.getSequence());
                event.setSequence(board.sequence);
                if (event.getEventId() == null) event.setEventId(UUID.randomUUID().toString());
                if ("clear".equals(event.getType())) { board.events.clear(); board.eventIds.clear(); board.strokeOwners.clear(); }
                board.events.add(event);
                board.eventIds.add(event.getEventId());
                if (event.getStrokeId() != null && event.getAuthorId() != null) board.strokeOwners.put(event.getStrokeId(), event.getAuthorId());
            }
        } catch (Exception e) { throw new IllegalStateException("Unable to load the saved board.", e); }
        // Rehydrate Redis before accepting new events, otherwise an old checkpoint could be lost.
        if ((live == null || live.isEmpty()) && !board.events.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(EVENTS + code, board.events.stream().map(this::json).toList());
            redisTemplate.expire(EVENTS + code, 24, TimeUnit.HOURS);
        }
        board.dirty = !board.events.isEmpty();
        redisTemplate.delete(MEMBERS + code); // Remove stale sessions from an earlier server process.
        boards.put(code, board);
        return board;
    }

    private void requireActive(Room room) {
        if (!room.isActive() || !room.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new NoSuchElementException("Room not found or expired.");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Unable to serialize board.", e); }
    }

    private void publishCount(String code, Board board) {
        messagingTemplate.convertAndSend("/topic/room/" + code + "/users", Map.of("connectedUsers", board.sessions.size()));
    }

    private void saveSnapshot(Board board) {
        if (!board.dirty) return;
        if (!board.room.getExpiresAt().isAfter(LocalDateTime.now())) board.room.setActive(false);
        board.room.setCanvasSnapshot(json(board.events));
        board.room.setSnapshotUpdatedAt(LocalDateTime.now());
        roomRepository.saveAndFlush(board.room);
        board.dirty = false;
    }

    @PreDestroy
    public void flushOnShutdown() { saveCanvasSnapshots(); }

    @Scheduled(fixedDelay = 30_000)
    public void saveCanvasSnapshots() {
        for (String code : List.copyOf(boards.keySet())) {
            synchronized (lock(code)) {
                Board board = boards.get(code);
                if (board == null) continue;
                try {
                    saveSnapshot(board);
                    if (board.sessions.isEmpty()) boards.remove(code);
                } catch (RuntimeException e) { log.error("Snapshot save failed for {}", code, e); }
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredRooms() {
        roomRepository.deactivateExpiredRooms(LocalDateTime.now());
        for (String code : List.copyOf(boards.keySet())) {
            synchronized (lock(code)) {
                Board board = boards.get(code);
                if (board != null && !board.room.getExpiresAt().isAfter(LocalDateTime.now())) {
                    messagingTemplate.convertAndSend("/topic/room/" + code + "/status", Map.of("expired", true));
                    boards.remove(code);
                    redisTemplate.delete(List.of(EVENTS + code, MEMBERS + code));
                }
            }
        }
    }
}
