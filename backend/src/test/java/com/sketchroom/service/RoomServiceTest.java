package com.sketchroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sketchroom.dto.DrawEvent;
import com.sketchroom.model.Room;
import com.sketchroom.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private RoomRepository repository;
    private RedisTemplate<String, String> redis;
    private ListOperations<String, String> lists;
    private SimpMessagingTemplate messages;
    private Room room;
    private RoomService service;
    private final Map<String, List<String>> storage = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        repository = mock(RoomRepository.class);
        redis = mock(RedisTemplate.class);
        lists = mock(ListOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        messages = mock(SimpMessagingTemplate.class);
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForSet()).thenReturn(sets);
        when(lists.range(anyString(), eq(0L), eq(-1L))).thenAnswer(i -> new ArrayList<>(storage.getOrDefault(i.getArgument(0), List.of())));
        when(lists.rightPush(anyString(), anyString())).thenAnswer(i -> {
            List<String> values = storage.computeIfAbsent(i.getArgument(0), key -> new ArrayList<>());
            values.add(i.getArgument(1)); return (long) values.size();
        });
        when(lists.rightPushAll(anyString(), anyCollection())).thenAnswer(i -> {
            List<String> values = storage.computeIfAbsent(i.getArgument(0), key -> new ArrayList<>());
            values.addAll(i.getArgument(1)); return (long) values.size();
        });
        doAnswer(i -> {
            List<String> values = storage.get(i.getArgument(0));
            if (values != null && !values.isEmpty()) storage.put(i.getArgument(0), new ArrayList<>(List.of(values.get(values.size() - 1))));
            return null;
        }).when(lists).trim(anyString(), eq(-1L), eq(-1L));
        room = Room.builder().id(1L).roomKey("ABC234").active(true)
                .createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusHours(24)).build();
        when(repository.findByRoomKeyAndActiveTrue("ABC234")).thenReturn(Optional.of(room));
        service = newService();
    }

    private RoomService newService() { return new RoomService(repository, redis, mapper, messages); }
    private DrawEvent draw(String id) {
        return DrawEvent.builder().type("draw").eventId(id).strokeId("stroke-" + id)
                .x(20d).y(30d).prevX(10d).prevY(15d).color("#123456").size(4).isEraser(false).build();
    }

    @Test void clearSurvivesARejoinAndRedisLoss() throws Exception {
        service.joinSession("ABC234", "alice");
        service.applyEvent("ABC234", "alice", draw("one"));
        service.saveCanvasSnapshots();
        service.applyEvent("ABC234", "alice", DrawEvent.builder().type("clear").eventId("clear").build());
        assertEquals("clear", mapper.readTree(room.getCanvasSnapshot()).get(0).get("type").asText());
        storage.clear();
        var snapshot = newService().joinSession("abc234", "bob");
        assertEquals(1, snapshot.events().size());
        assertEquals("clear", snapshot.events().get(0).getType());
        assertEquals(2, snapshot.sequence());
    }

    @Test void retainsMoreThanTwoThousandEvents() {
        service.joinSession("ABC234", "alice");
        for (int i = 0; i < 2100; i++) service.applyEvent("ABC234", "alice", draw("event-" + i));
        assertEquals(2100, service.joinSession("ABC234", "bob").events().size());
        verify(lists, never()).trim(anyString(), eq(-2000L), eq(-1L));
    }

    @Test void rejectsAtLimitWithoutDeletingOldDrawingAndStillAllowsClear() {
        ReflectionTestUtils.setField(service, "maxEvents", 1);
        service.joinSession("ABC234", "alice");
        service.applyEvent("ABC234", "alice", draw("one"));
        assertThrows(IllegalArgumentException.class, () -> service.applyEvent("ABC234", "alice", draw("two")));
        assertEquals(1, service.joinSession("ABC234", "bob").events().size());
        service.applyEvent("ABC234", "alice", DrawEvent.builder().type("clear").build());
    }

    @Test void reconnectReceivesMissedEventsAndCanonicalCode() {
        service.joinSession("abc234", "alice");
        service.applyEvent("ABC234", "alice", draw("one"));
        var snapshot = service.joinSession("ABC234", "reconnected");
        assertEquals("ABC234", snapshot.roomCode());
        assertEquals(1, snapshot.sequence());
        assertEquals(1, snapshot.events().size());
    }

    @Test void ignoresDuplicateEventIds() {
        service.joinSession("ABC234", "alice");
        service.applyEvent("ABC234", "alice", draw("same"));
        service.applyEvent("ABC234", "alice", draw("same"));
        assertEquals(1, service.joinSession("ABC234", "alice").sequence());
    }

    @Test void enforcesExpiryAndMembership() {
        service.joinRoom("ABC234");
        assertThrows(IllegalArgumentException.class, () -> service.applyEvent("ABC234", "outsider", draw("one")));
        room.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertFalse(service.joinRoom("ABC234").isSuccess());
        assertThrows(NoSuchElementException.class, () -> service.joinSession("ABC234", "alice"));
    }

    @Test void rejectsMalformedDrawingAndOtherUsersUndo() {
        service.joinSession("ABC234", "alice");
        service.joinSession("ABC234", "bob");
        DrawEvent invalid = draw("invalid"); invalid.setX(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> service.applyEvent("ABC234", "alice", invalid));
        service.applyEvent("ABC234", "alice", draw("one"));
        DrawEvent undo = DrawEvent.builder().type("undo").strokeId("stroke-one").build();
        assertThrows(IllegalArgumentException.class, () -> service.applyEvent("ABC234", "bob", undo));
        service.applyEvent("ABC234", "alice", undo);
        assertEquals("undo", service.joinSession("ABC234", "alice").events().get(1).getType());
    }

    @Test void broadcastsReducedCountOnDisconnect() {
        service.joinSession("ABC234", "alice"); service.joinSession("ABC234", "bob");
        clearInvocations(messages);
        service.removeUserFromRoom("ABC234", "alice");
        verify(messages).convertAndSend("/topic/room/ABC234/users", Map.of("connectedUsers", 1));
    }

    @Test void oldDatabaseSnapshotIsRehydratedBeforeNewDrawing() throws Exception {
        room.setCanvasSnapshot("[{\"type\":\"draw\",\"x\":1,\"y\":2,\"prevX\":0,\"prevY\":0,\"color\":\"#000000\",\"size\":4,\"isEraser\":false}]");
        service.joinSession("ABC234", "alice");
        service.applyEvent("ABC234", "alice", draw("new"));
        var snapshot = newService().joinSession("ABC234", "bob");
        assertEquals(2, snapshot.events().size());
        assertEquals(2, snapshot.sequence());
    }

    @Test void storageFailureDoesNotBroadcastUnpersistedDrawing() {
        service.joinSession("ABC234", "alice"); clearInvocations(messages);
        when(lists.rightPush(anyString(), anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertThrows(IllegalStateException.class, () -> service.applyEvent("ABC234", "alice", draw("one")));
        verify(messages, never()).convertAndSend(eq("/topic/room/ABC234"), any(Object.class));
        assertEquals(0, service.joinSession("ABC234", "alice").sequence());
    }

    @Test void concurrentEventsHaveOneServerOrder() throws Exception {
        service.joinSession("ABC234", "alice");
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String id = "event-" + i;
                jobs.add(() -> { service.applyEvent("ABC234", "alice", draw(id)); return null; });
            }
            for (Future<Void> result : workers.invokeAll(jobs)) result.get();
            var events = service.joinSession("ABC234", "alice").events();
            for (int i = 0; i < events.size(); i++) assertEquals(i + 1L, events.get(i).getSequence());
        } finally { workers.shutdownNow(); }
    }
}
