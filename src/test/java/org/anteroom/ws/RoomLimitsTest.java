package org.anteroom.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.anteroom.auth.Ed25519Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Потолки попыток и пропуск на создание комнаты.
 *
 * <p>Отдельным классом: у этих проверок потолки должны быть низкими, а тестам протокола
 * низкие потолки только мешают — они заводят десятки комнат с одного адреса.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.data-dir=build/test-data-limits",
                "app.challenge.per-ip-limit=10000",
                "app.work.bits=6",
                "app.limit.create-per-hour=1",
                // Потолок на адрес держим высоким намеренно: иначе непонятно, какой из двух
                // счётчиков отбил комнату, и тест перестаёт что-либо доказывать.
                "app.limit.create-per-hour-ip=100",
                "app.limit.invite-per-hour=1",
                "app.limit.write-per-minute=2" })
class RoomLimitsTest {

    private static final Path DATA_DIR = Path.of("build/test-data-limits");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

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

    @Test
    void demandsProofOfWorkForANewRoom() throws Exception {
        try (Client owner = new Client()) {
            JsonNode refused = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}");

            assertThat(refused.get("op").asText()).isEqualTo("error");
            assertThat(refused.get("reason").asText()).contains("пропуск");
        }
    }

    @Test
    void letsThroughARoomWithAnHonestStamp() throws Exception {
        try (Client owner = new Client()) {
            assertThat(owner.createRoom().get("op").asText()).isEqualTo("room");
        }
    }

    @Test
    void refusesToReuseTheSameStamp() throws Exception {
        // Иначе одна решённая задача открывает сколько угодно комнат.
        try (Client owner = new Client()) {
            JsonNode stamp = owner.request("{\"op\":\"work\"}");
            String salt = stamp.get("salt").asText();
            String counter = solve(salt, stamp.get("bits").asInt());
            owner.request(createFrame(salt, counter));

            JsonNode again = owner.request(createFrame(salt, counter));

            assertThat(again.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void refusesASecondRoomWithinTheWindow() throws Exception {
        try (Client owner = new Client()) {
            assertThat(owner.createRoom().get("op").asText()).isEqualTo("room");

            JsonNode second = owner.createRoom();

            assertThat(second.get("op").asText()).isEqualTo("error");
            assertThat(second.get("reason").asText()).contains("слишком часто");
        }
    }

    @Test
    void refusesWritesBeyondTheAllowance() throws Exception {
        try (Client owner = new Client()) {
            String roomId = owner.createRoom().get("room").asText();
            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();

            for (int i = 0; i < 2; i++) {
                owner.send("{\"op\":\"send\",\"room\":\"" + roomId + "\",\"ciphertext\":\"AQID\"}");
                assertThat(owner.next().get("op").asText()).as("реплика " + i).isEqualTo("msg");
            }

            JsonNode answer = owner.request(
                    "{\"op\":\"send\",\"room\":\"" + roomId + "\",\"ciphertext\":\"AQID\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void countsRecordsOfAllKindsAgainstOneAllowance() throws Exception {
        // Иначе спам просто перетекает из ленты в записки и личные сообщения.
        try (Client owner = new Client()) {
            String roomId = owner.createRoom().get("room").asText();
            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();

            owner.send("{\"op\":\"send\",\"room\":\"" + roomId + "\",\"ciphertext\":\"AQID\"}");
            assertThat(owner.next().get("op").asText()).isEqualTo("msg");
            owner.send("{\"op\":\"once\",\"room\":\"" + roomId
                    + "\",\"tokenHash\":\"записка\",\"ciphertext\":\"AQID\"}");
            assertThat(owner.next().get("op").asText()).isEqualTo("once-stored");

            JsonNode answer = owner.request("{\"op\":\"once\",\"room\":\"" + roomId
                    + "\",\"tokenHash\":\"вторая\",\"ciphertext\":\"AQID\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    private static String createFrame(String salt, String counter) {
        return "{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400,\"salt\":\"" + salt
                + "\",\"counter\":\"" + counter + "\"}";
    }

    /** Решает задачу тем же перебором, что и браузер. */
    private static String solve(String salt, int bits) {
        for (long counter = 0; counter < 10_000_000; counter++) {
            byte[] digest = sha256(salt + ":" + counter);
            int zeros = 0;
            for (byte value : digest) {
                if (value == 0) {
                    zeros += 8;
                    continue;
                }
                zeros += Integer.numberOfLeadingZeros(value & 0xff) - 24;
                break;
            }
            if (zeros >= bits) {
                return String.valueOf(counter);
            }
        }
        throw new AssertionError("не решилось");
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class Client extends TextWebSocketHandler implements AutoCloseable {

        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final WebSocketSession session;

        private Client() throws Exception {
            KeyPair device = Ed25519Keys.newKeyPair();
            String publicKey = ENCODER.encodeToString(Ed25519Keys.rawPublicKey(device.getPublic()));

            @SuppressWarnings("unchecked")
            Map<String, String> challenge = rest.getForObject("/api/challenge", Map.class);
            String nonce = challenge.get("nonce");
            String signature = ENCODER.encodeToString(
                    Ed25519Keys.sign(Base64.getUrlDecoder().decode(nonce), device.getPrivate()));

            this.session = new StandardWebSocketClient().execute(this, null,
                    URI.create("ws://localhost:" + port + "/ws?nonce=" + nonce
                            + "&device=" + publicKey + "&signature=" + signature))
                    .get(5, TimeUnit.SECONDS);

            jdbc.update("INSERT OR IGNORE INTO instance_owner (pubkey_sign, granted_at) VALUES (?, 0)",
                    publicKey);
        }

        JsonNode createRoom() throws Exception {
            JsonNode stamp = request("{\"op\":\"work\"}");
            return request(createFrame(stamp.get("salt").asText(),
                    solve(stamp.get("salt").asText(), stamp.get("bits").asInt())));
        }

        void send(String payload) throws IOException {
            session.sendMessage(new TextMessage(payload));
        }

        JsonNode request(String payload) throws Exception {
            send(payload);
            return next();
        }

        JsonNode next() throws Exception {
            String frame = received.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("сервер не ответил за 5 секунд").isNotNull();
            return JSON.readTree(frame);
        }

        void drain() throws InterruptedException {
            while (received.poll(300, TimeUnit.MILLISECONDS) != null) {
                // подчищаем ответы на подготовительные кадры
            }
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
