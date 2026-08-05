package org.anteroom.config;

import org.anteroom.ws.RoomSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RoomSocketHandler handler;

    public WebSocketConfig(RoomSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Сырой обработчик, без STOMP: по проводу летят непрозрачные блобы, роутить нечего.
        registry.addHandler(handler, "/ws");
    }
}
