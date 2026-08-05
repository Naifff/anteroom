package org.anteroom.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.anteroom.auth.Ed25519Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.data-dir=build/test-data-ws")
class RoomSocketHandlerTest {

    private static final Path DATA_DIR = Path.of("build/test-data-ws");

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate rest;

    @BeforeAll
    static void startFromEmptyDirectory() throws IOException {
        if (!Files.exists(DATA_DIR)) {
            return;
        }
        try (var paths = Files.walk(DATA_DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @BeforeEach
    void clearFeed() {
        jdbc.update("DELETE FROM message");
    }

    @Test
    void deliversMessageToOtherSessionInRoom() throws Exception {
        try (Client sender = connect(); Client listener = connect()) {
            sender.send("{\"ciphertext\":\"0J/RgNC40LLQtdGC\"}");

            assertThat(listener.next()).contains("0J/RgNC40LLQtdGC");
        }
    }

    @Test
    void echoesMessageBackToItsSender() throws Exception {
        // Отправитель — такой же участник комнаты: его вкладка рисует своё сообщение
        // только когда сервер подтвердил запись и выдал id.
        try (Client sender = connect()) {
            sender.send("{\"ciphertext\":\"0J/RgNC40LLQtdGC\"}");

            assertThat(sender.next()).contains("0J/RgNC40LLQtdGC");
        }
    }

    @Test
    void deliversBacklogAfterReconnect() throws Exception {
        try (Client first = connect()) {
            first.send("{\"ciphertext\":\"0YHRgtCw0YDQvtC1\"}");
            first.next();
        }

        try (Client reconnected = connect()) {
            reconnected.send("{\"since\":0}");

            assertThat(reconnected.next()).contains("0YHRgtCw0YDQvtC1");
        }
    }

    @Test
    void skipsBacklogAlreadySeen() throws Exception {
        long lastSeen;
        try (Client first = connect()) {
            first.send("{\"ciphertext\":\"0YHRgtCw0YDQvtC1\"}");
            lastSeen = idOf(first.next());
        }

        try (Client reconnected = connect()) {
            reconnected.send("{\"since\":" + lastSeen + "}");
            reconnected.send("{\"ciphertext\":\"0L3QvtCy0L7QtQ==\"}");

            assertThat(reconnected.next()).contains("0L3QvtCy0L7QtQ==");
        }
    }

    @Test
    void forgetsSessionAfterClose() throws Exception {
        try (Client client = connect()) {
            client.send("{\"since\":0}");
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> registry.sessions(RoomSocketHandler.SKELETON_ROOM).size() == 1);
        }

        // Реконнект не должен оставлять в реестре осиротевшую сессию: сообщения ушли бы
        // в закрытый сокет, а комната никогда бы не опустела.
        await().atMost(Duration.ofSeconds(5)).until(() -> registry.roomCount() == 0);
    }

    private static long idOf(String frame) {
        int from = frame.indexOf("\"id\":") + 5;
        int to = frame.indexOf(',', from);
        return Long.parseLong(frame.substring(from, to).trim());
    }

    /**
     * Каждое соединение проходит настоящий вход: свой вызов, своя подпись. Одним вызовом
     * два соединения не поднять — он гасится при использовании, и это проверяет
     * {@code AuthHandshakeInterceptorTest}.
     */
    private Client connect() throws Exception {
        KeyPair device = Ed25519Keys.newKeyPair();
        @SuppressWarnings("unchecked")
        Map<String, String> challenge = rest.getForObject("/api/challenge", Map.class);
        String nonce = challenge.get("nonce");

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String signature = encoder.encodeToString(
                Ed25519Keys.sign(Base64.getUrlDecoder().decode(nonce), device.getPrivate()));
        String pubkey = encoder.encodeToString(Ed25519Keys.rawPublicKey(device.getPublic()));

        return new Client(URI.create("ws://localhost:" + port + "/ws"
                + "?nonce=" + nonce + "&device=" + pubkey + "&signature=" + signature));
    }

    private static final class Client extends TextWebSocketHandler implements AutoCloseable {

        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final WebSocketSession session;

        private Client(URI uri) throws Exception {
            this.session = new StandardWebSocketClient().execute(this, null, uri).get(5, TimeUnit.SECONDS);
        }

        void send(String payload) throws IOException {
            session.sendMessage(new TextMessage(payload));
        }

        String next() throws InterruptedException {
            String frame = received.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("сервер не прислал кадр за 5 секунд").isNotNull();
            return frame;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(message.getPayload());
        }

        @Override
        public void close() throws Exception {
            session.close(CloseStatus.NORMAL);
        }
    }
}
