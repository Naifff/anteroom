package org.anteroom.ws;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import org.anteroom.device.DeviceService;
import org.anteroom.message.MessageService;
import org.anteroom.message.StoredMessage;
import org.anteroom.room.DeckSpentException;
import org.anteroom.room.KeyEpochService;
import org.anteroom.room.Member;
import org.anteroom.room.Room;
import org.anteroom.room.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Протокол комнаты.
 *
 * <p>Сервер не разбирает содержимое сообщений и не может: это шифротекст, ключ от которого
 * живёт в браузерах участников. Здесь только маршрутизация непрозрачных блобов, раздача
 * чужих обёрток ключа и учёт, кто в какой комнате.
 *
 * <p>Каждое действие проверяет членство отдельно. Проверить один раз на входе в комнату
 * недостаточно: между входом и отправкой участника могли исключить.
 */
@Component
public class RoomSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomSocketHandler.class);

    /** Комната, чью ленту сейчас слушает сессия. */
    private static final String ROOM_ATTRIBUTE = "room";

    private final SessionRegistry registry;
    private final MessageService messages;
    private final RoomService rooms;
    private final KeyEpochService epochs;
    private final DeviceService devices;
    private final ObjectMapper json = new ObjectMapper();

    public RoomSocketHandler(SessionRegistry registry, MessageService messages, RoomService rooms,
                             KeyEpochService epochs, DeviceService devices) {
        this.registry = registry;
        this.messages = messages;
        this.rooms = rooms;
        this.epochs = epochs;
        this.devices = devices;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        String device = device(session);
        JsonNode frame;
        try {
            frame = json.readTree(message.getPayload());
        } catch (IOException e) {
            fail(session, "кадр не разбирается");
            return;
        }

        String op = frame.path("op").asText("");
        try {
            switch (op) {
                case "hello" -> devices.rememberEncryptionKey(device, frame.path("box").asText());
                case "create" -> create(session, device, frame);
                case "join" -> join(session, device, frame);
                case "enter" -> enter(session, device, frame);
                case "key" -> key(session, device, frame);
                case "wrap" -> wrap(session, device, frame);
                case "send" -> send(session, device, frame);
                default -> fail(session, "неизвестная операция");
            }
        } catch (DeckSpentException e) {
            // Не ошибка выдачи, а конец жизни комнаты — и называется отдельно, чтобы
            // интерфейс мог сказать это словами, а не показать общий отказ.
            fail(session, "колода кончилась");
        } catch (IllegalArgumentException e) {
            fail(session, e.getMessage());
        }
    }

    private void create(WebSocketSession session, String device, JsonNode frame) throws IOException {
        String roomId = rooms.create(device,
                frame.path("defaultTtl").asLong(RoomService.TTL_DEFAULT),
                frame.path("maxTtl").asLong(RoomService.TTL_CEILING));
        sendRoom(session, device, roomId);
    }

    private void join(WebSocketSession session, String device, JsonNode frame) throws IOException {
        // Инвайтов до фазы 6 нет, поэтому вход в комнату пока открыт всякому, кто знает
        // её номер. Наружу не выставлять.
        String roomId = frame.path("room").asText();
        rooms.join(roomId, device, "member", null);
        sendRoom(session, device, roomId);

        // Сидящим — обновлённый состав. Карта это имя участника, и без рассылки они видели бы
        // у новичка отпечаток вместо карты, пока не переоткроют вкладку. Заодно это сигнал
        // раздать новичку обёртку ключа: сам он её взять неоткуда.
        announceRoster(roomId, session);
    }

    private void announceRoster(String roomId, WebSocketSession except) {
        for (WebSocketSession listener : registry.sessions(roomId)) {
            if (listener == except) {
                continue;
            }
            try {
                sendRoom(listener, device(listener), roomId);
            } catch (IOException e) {
                log.debug("Состав не ушёл, сессия снимается: {}", e.toString());
                registry.unregister(roomId, listener);
            }
        }
    }

    private void enter(WebSocketSession session, String device, JsonNode frame) throws IOException {
        String roomId = frame.path("room").asText();
        requireMember(roomId, device);

        registry.register(roomId, session);
        session.getAttributes().put(ROOM_ATTRIBUTE, roomId);

        sendRoom(session, device, roomId);
        for (StoredMessage stored : messages.since(roomId, frame.path("since").asLong())) {
            send(session, roomId, stored);
        }
    }

    private void key(WebSocketSession session, String device, JsonNode frame) throws IOException {
        String roomId = frame.path("room").asText();
        requireMember(roomId, device);

        byte[] wrapped = epochs.wrappedKey(roomId, device, frame.path("epoch").asInt());
        ObjectNode answer = json.createObjectNode();
        answer.put("op", "key");
        answer.put("room", roomId);
        answer.put("epoch", frame.path("epoch").asInt());
        // Обёртки может ещё не быть: раздающий ключ участник кладёт её не мгновенно.
        answer.put("wrapped", wrapped == null ? null : Base64.getEncoder().encodeToString(wrapped));
        write(session, answer);
    }

    private void wrap(WebSocketSession session, String device, JsonNode frame) throws IOException {
        String roomId = frame.path("room").asText();
        requireMember(roomId, device);

        String forDevice = frame.path("device").asText();
        requireMember(roomId, forDevice);

        epochs.storeWrappedKey(roomId, forDevice, frame.path("epoch").asInt(),
                Base64.getDecoder().decode(frame.path("wrapped").asText()));
    }

    private void send(WebSocketSession session, String device, JsonNode frame) throws IOException {
        String roomId = frame.path("room").asText();
        requireMember(roomId, device);

        byte[] ciphertext = Base64.getDecoder().decode(frame.path("ciphertext").asText());
        Room room = rooms.find(roomId);
        StoredMessage saved = messages.save(roomId, device, room.keyEpoch(), ciphertext, room.defaultTtl());

        // Отправителю тоже: его вкладка рисует сообщение по подтверждению с id, а не сразу,
        // иначе после реконнекта оно задвоится с тем, что придёт из догрузки.
        for (WebSocketSession listener : registry.sessions(roomId)) {
            try {
                send(listener, roomId, saved);
            } catch (IOException e) {
                // Одна отвалившаяся вкладка не должна лишать сообщения остальных.
                log.debug("Кадр не ушёл, сессия снимается: {}", e.toString());
                registry.unregister(roomId, listener);
            }
        }
    }

    private void sendRoom(WebSocketSession session, String device, String roomId) throws IOException {
        Room room = rooms.find(roomId);
        List<Member> members = rooms.members(roomId);
        var boxes = devices.encryptionKeys(members.stream().map(Member::pubkeySign).toList());

        ObjectNode answer = json.createObjectNode();
        answer.put("op", "room");
        answer.put("room", roomId);
        answer.put("epoch", room.keyEpoch());
        answer.put("card", rooms.card(roomId, device));
        answer.put("seatsTaken", room.seatsTaken());
        answer.put("deckSpent", room.deckSpent());

        ArrayNode seats = answer.putArray("members");
        for (Member member : members) {
            ObjectNode seat = seats.addObject();
            seat.put("device", member.pubkeySign());
            seat.put("card", member.card());
            seat.put("box", boxes.get(member.pubkeySign()));
        }

        // Кому обёртки текущей эпохи ещё не положили. Раздать их может любой, у кого ключ
        // комнаты уже есть, — сервер тут только счетовод.
        ArrayNode waiting = answer.putArray("needWrap");
        for (String pending : epochs.membersWithoutWrapper(roomId, room.keyEpoch())) {
            waiting.addObject().put("device", pending).put("box", boxes.get(pending));
        }

        write(session, answer);
    }

    private void send(WebSocketSession session, String roomId, StoredMessage message) throws IOException {
        ObjectNode frame = json.createObjectNode();
        frame.put("op", "msg");
        frame.put("room", roomId);
        frame.put("id", message.id());
        frame.put("sender", message.sender());
        frame.put("epoch", message.epoch());
        frame.put("ciphertext", Base64.getEncoder().encodeToString(message.ciphertext()));
        write(session, frame);
    }

    private void fail(WebSocketSession session, String reason) throws IOException {
        ObjectNode frame = json.createObjectNode();
        frame.put("op", "error");
        frame.put("reason", reason);
        write(session, frame);
    }

    private void requireMember(String roomId, String device) {
        if (rooms.role(roomId, device) == null) {
            // Один и тот же отказ на «нет такой комнаты» и «вы не участник»: различать их
            // снаружи означало бы отдавать номера существующих комнат перебором.
            throw new IllegalArgumentException("нет доступа к комнате");
        }
    }

    private void write(WebSocketSession session, ObjectNode frame) throws IOException {
        // Сериализация и отправка под замком сессии: Undertow не гарантирует потокобезопасность
        // sendMessage, а сюда одновременно приходят и рассылка, и ответы на запросы.
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
            }
        }
    }

    private static String device(WebSocketSession session) {
        return (String) session.getAttributes().get(AuthHandshakeInterceptor.DEVICE_ATTRIBUTE);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        forget(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // Без этого сессия остаётся в реестре навсегда: afterConnectionClosed при обрыве
        // приходит не всегда, а рассылка в мёртвый сокет валит рассылку остальным.
        log.debug("Сессия снята по ошибке транспорта: {}", exception.toString());
        forget(session);
    }

    private void forget(WebSocketSession session) {
        String roomId = (String) session.getAttributes().get(ROOM_ATTRIBUTE);
        if (roomId != null) {
            registry.unregister(roomId, session);
        }
    }
}
