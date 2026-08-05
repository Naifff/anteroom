package org.anteroom.ws;

import java.io.IOException;
import java.util.Base64;

import org.anteroom.message.MessageService;
import org.anteroom.message.StoredMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Скелет ленты: одна комната, приём блоба, запись и рассылка живым сессиям.
 *
 * <p>Сервер не разбирает содержимое {@code ciphertext} и не может: это шифротекст,
 * ключ от которого живёт во фрагменте ссылки и на сервер не приходит никогда.
 *
 * <p>Аутентификации здесь нет — она фаза 4. До неё эндпоинт открыт, и это единственная
 * причина, по которой скелет нельзя выкладывать наружу.
 */
@Component
public class RoomSocketHandler extends TextWebSocketHandler {

    public static final String SKELETON_ROOM = "skeleton";

    private static final Logger log = LoggerFactory.getLogger(RoomSocketHandler.class);

    private final SessionRegistry registry;
    private final MessageService messages;
    private final ObjectMapper json = new ObjectMapper();

    public RoomSocketHandler(SessionRegistry registry, MessageService messages) {
        this.registry = registry;
        this.messages = messages;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.register(SKELETON_ROOM, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode frame = json.readTree(message.getPayload());

        if (frame.hasNonNull("since")) {
            for (StoredMessage stored : messages.since(SKELETON_ROOM, frame.get("since").asLong())) {
                send(session, stored);
            }
            return;
        }

        byte[] ciphertext = Base64.getDecoder().decode(frame.get("ciphertext").asText());

        // Отправитель — ключ подписи, проверенный на апгрейде, а не id сессии: id живёт
        // одно соединение, а автор реплики должен быть узнаваем и после реконнекта.
        String sender = (String) session.getAttributes().get(AuthHandshakeInterceptor.DEVICE_ATTRIBUTE);

        // Отправителю тоже: его вкладка рисует сообщение по подтверждению с id, а не сразу,
        // иначе после реконнекта оно задвоится с тем, что придёт из догрузки.
        broadcast(messages.save(SKELETON_ROOM, sender, 1, ciphertext));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(SKELETON_ROOM, session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // Без этого сессия остаётся в реестре навсегда: afterConnectionClosed при обрыве
        // приходит не всегда, а рассылка в мёртвый сокет валит рассылку остальным.
        log.debug("Сессия снята по ошибке транспорта: {}", exception.toString());
        registry.unregister(SKELETON_ROOM, session);
    }

    private void broadcast(StoredMessage message) {
        for (WebSocketSession session : registry.sessions(SKELETON_ROOM)) {
            try {
                send(session, message);
            } catch (IOException e) {
                // Одна отвалившаяся вкладка не должна лишать сообщения остальных.
                log.debug("Не удалось отправить кадр, сессия снимается: {}", e.toString());
                registry.unregister(SKELETON_ROOM, session);
            }
        }
    }

    private void send(WebSocketSession session, StoredMessage message) throws IOException {
        ObjectNode frame = json.createObjectNode();
        frame.put("id", message.id());
        frame.put("sender", message.sender());
        frame.put("ciphertext", Base64.getEncoder().encodeToString(message.ciphertext()));

        // Сериализация и отправка под замком сессии: Undertow не гарантирует потокобезопасность
        // sendMessage, а сюда одновременно приходят и рассылка, и догрузка по since.
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
            }
        }
    }
}
