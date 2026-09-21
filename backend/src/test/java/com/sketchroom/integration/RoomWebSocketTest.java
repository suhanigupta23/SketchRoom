package com.sketchroom.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sketchroom.model.Room;
import com.sketchroom.repository.RoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP + STOMP transport, with storage mocked so no developer database is touched. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "app.websocket.url="
})
class RoomWebSocketTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean RoomRepository repository;
    @MockitoBean RedisTemplate<String, String> redisTemplate;
    @MockitoBean LettuceConnectionFactory connectionFactory;

    @Test
    @SuppressWarnings("unchecked")
    void twoClientsDrawUndoClearAndReconnectThroughRealStomp() throws Exception {
        Room room = Room.builder().id(1L).roomKey("ABC234").active(true)
                .createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusHours(24)).build();
        when(repository.findByRoomKeyAndActiveTrue("ABC234")).thenReturn(Optional.of(room));
        ListOperations<String, String> lists = mock(ListOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redisTemplate.opsForList()).thenReturn(lists);
        when(redisTemplate.opsForSet()).thenReturn(sets);
        when(lists.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());

        HttpClient http = HttpClient.newHttpClient();
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/rooms/join"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"roomCode\":\"abc234\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ABC234", mapper.readTree(response.body()).get("roomCode").asText());
        try (Peer alice = connect(http); Peer bob = connect(http)) {
            alice.subscribe("snapshot", "/user/queue/snapshot");
            alice.subscribe("events", "/topic/room/ABC234");
            alice.subscribe("users", "/topic/room/ABC234/users");
            alice.send("/app/join/ABC234", "{}");
            assertEquals(0, alice.receive("snapshot").get("sequence").asLong());
            bob.subscribe("snapshot", "/user/queue/snapshot");
            bob.subscribe("events", "/topic/room/ABC234");
            bob.subscribe("errors", "/user/queue/errors");
            bob.send("/app/join/ABC234", "{}");
            assertEquals(2, bob.receive("snapshot").get("connectedUsers").asInt());
            alice.send("/app/draw/ABC234", "{\"type\":\"draw\",\"eventId\":\"first\",\"strokeId\":\"stroke\",\"x\":20,\"y\":30,\"prevX\":10,\"prevY\":15,\"color\":\"#123456\",\"size\":4,\"isEraser\":false}");
            assertEquals(1, bob.receive("events").get("sequence").asLong());
            String segment = "{\"type\":\"draw\",\"eventId\":\"batch-one\",\"strokeId\":\"stroke\",\"x\":30,\"y\":30,\"prevX\":20,\"prevY\":30,\"color\":\"#123456\",\"size\":4,\"isEraser\":false}";
            alice.send("/app/draw-batch/ABC234", "[" + segment + "," + segment.replace("batch-one", "batch-two") + "]");
            assertEquals(2, bob.receive("events").get("sequence").asLong());
            assertEquals(3, bob.receive("events").get("sequence").asLong());
            bob.send("/app/draw/ABC234", "{\"type\":\"undo\",\"eventId\":\"bad-undo\",\"strokeId\":\"stroke\"}");
            assertEquals("INVALID_EVENT", bob.receive("errors").get("error").asText());
            alice.send("/app/draw/ABC234", "{\"type\":\"undo\",\"eventId\":\"undo\",\"strokeId\":\"stroke\"}");
            assertEquals("undo", bob.receive("events").get("type").asText());
            alice.send("/app/draw/ABC234", "{\"type\":\"clear\",\"eventId\":\"clear\"}");
            assertEquals("clear", bob.receive("events").get("type").asText());
            try (Peer reconnected = connect(http)) {
                reconnected.subscribe("snapshot", "/user/queue/snapshot");
                reconnected.send("/app/join/ABC234", "{}");
                JsonNode snapshot = reconnected.receive("snapshot");
                assertEquals(5, snapshot.get("sequence").asLong());
                assertEquals(1, snapshot.get("events").size());
                assertEquals("clear", snapshot.get("events").get(0).get("type").asText());
            }
        }
    }

    private Peer connect(HttpClient http) throws Exception {
        Peer peer = new Peer();
        peer.socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + "/ws/websocket"), peer).get(10, TimeUnit.SECONDS);
        peer.socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\n\n\0", true).join();
        assertTrue(peer.frames.poll(10, TimeUnit.SECONDS).startsWith("CONNECTED"));
        return peer;
    }

    private class Peer implements WebSocket.Listener, AutoCloseable {
        WebSocket socket;
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { frames.add(partial.toString()); partial.setLength(0); }
            webSocket.request(1); return null;
        }
        void subscribe(String id, String destination) {
            socket.sendText("SUBSCRIBE\nid:" + id + "\ndestination:" + destination + "\n\n\0", true).join();
        }
        void send(String destination, String body) {
            socket.sendText("SEND\ndestination:" + destination + "\ncontent-type:application/json\n\n" + body + "\0", true).join();
        }
        JsonNode receive(String subscription) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                String frame = frames.poll(1, TimeUnit.SECONDS);
                if (frame == null) continue;
                assertFalse(frame.startsWith("ERROR"), frame);
                if (frame.contains("subscription:" + subscription + "\n")) {
                    return mapper.readTree(frame.substring(frame.indexOf("\n\n") + 2).replace("\0", ""));
                }
            }
            fail("Timed out waiting for " + subscription); return null;
        }
        @Override public void close() { if (socket != null) socket.abort(); }
    }
}
