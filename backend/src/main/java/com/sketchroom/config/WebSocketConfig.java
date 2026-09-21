package com.sketchroom.config;

import com.sketchroom.service.RoomService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final ObjectProvider<RoomService> rooms;
    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    public WebSocketConfig(ObjectProvider<RoomService> rooms) { this.rooms = rooms; }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins.split(",")).withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024).setSendBufferSizeLimit(32 * 1024 * 1024)
                .setSendTimeLimit(30_000);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor headers = StompHeaderAccessor.wrap(message);
                String destination = headers.getDestination();
                if (headers.getCommand() == StompCommand.SEND) {
                    if (destination == null || !destination.matches("/app/(draw|clear|join)/[A-Z0-9]{6}")) {
                        throw new IllegalArgumentException("Unsupported message destination.");
                    }
                } else if (headers.getCommand() == StompCommand.SUBSCRIBE) {
                    if ("/user/queue/snapshot".equals(destination) || "/user/queue/errors".equals(destination)) return message;
                    if (destination == null || !destination.matches("/topic/room/[A-Z0-9]{6}(/users|/status)?")) {
                        throw new IllegalArgumentException("Unsupported subscription.");
                    }
                    String code = destination.split("/")[3];
                    rooms.getObject().validateRoom(code);
                }
                return message;
            }
        });
    }
}
