package com.sketchroom.config;

import com.sketchroom.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.*;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventListener {

    private final RoomService roomService;

    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.wrap(event.getMessage());
        log.debug("WebSocket connected: session={}",
            accessor.getSessionId());
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            String roomKey = (String) attrs.get("roomKey");
            if (roomKey != null) {
                roomService.removeUserFromRoom(roomKey, sessionId);

                log.info("Session {} disconnected from room {}",
                    sessionId, roomKey);
            }
        }
        log.debug("WebSocket disconnected: session={}", sessionId);
    }
}