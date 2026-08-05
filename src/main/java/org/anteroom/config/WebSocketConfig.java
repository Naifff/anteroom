package org.anteroom.config;

import org.anteroom.ws.AuthHandshakeInterceptor;
import org.anteroom.ws.RoomSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RoomSocketHandler handler;
    private final AuthHandshakeInterceptor auth;

    public WebSocketConfig(RoomSocketHandler handler, AuthHandshakeInterceptor auth) {
        this.handler = handler;
        this.auth = auth;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Сырой обработчик, без STOMP: по проводу летят непрозрачные блобы, роутить нечего.
        // Проверка подписи висит на апгрейде: неподписанное соединение не должно доживать
        // до обработчика вообще.
        registry.addHandler(handler, "/ws").addInterceptors(auth);
    }
}
