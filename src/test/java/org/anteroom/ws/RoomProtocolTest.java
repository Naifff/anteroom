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
import org.anteroom.invite.InviteHash;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "app.data-dir=build/test-data-protocol", "app.challenge.per-ip-limit=10000" })
class RoomProtocolTest {

    private static final Path DATA_DIR = Path.of("build/test-data-protocol");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SessionRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Пропуск на сервер выдаётся напрямую: как он выдаётся по owner-инвайту, проверяет
     * {@code OwnerBootstrapTest}, а здесь проверяется протокол комнат.
     */
    private void admit(String pubkeySign) {
        jdbc.update("INSERT OR IGNORE INTO instance_owner (pubkey_sign, granted_at) VALUES (?, 0)", pubkeySign);
    }

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
    void refusesRoomToDeviceNobodyInvited() throws Exception {
        // Подпись говорит «это тот же ключ», а не «его сюда звали». Иначе любой, кто открыл
        // адрес, заводит на чужом сервере свои комнаты.
        try (Client stranger = new Client(false)) {
            JsonNode answer = stranger.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void createsRoomAndSeatsCreator() throws Exception {
        try (Client owner = new Client()) {
            JsonNode room = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}");

            assertThat(room.get("op").asText()).isEqualTo("room");
            assertThat(room.get("room").asText()).isNotBlank();
            assertThat(room.get("epoch").asInt()).isEqualTo(1);
            assertThat(room.get("card").asInt()).isBetween(0, 51);
            // Своя роль нужна интерфейсу: приглашать может не всякий, и кнопку выпуска
            // показывать всем — обещать действие, которое сервер отклонит.
            assertThat(room.get("role").asText()).isEqualTo("owner");
        }
    }

    @Test
    void carriesMessageBetweenTwoMembersOfRoom() throws Exception {
        try (Client owner = new Client(); Client guest = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            inviteAndRedeem(owner, roomId, "для-гостя", guest);

            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            guest.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();
            guest.drain();

            owner.send("{\"op\":\"send\",\"room\":\"" + roomId + "\",\"ciphertext\":\"0J/RgNC40LLQtdGC\"}");

            JsonNode delivered = guest.awaitOp("msg");
            assertThat(delivered.get("ciphertext").asText()).isEqualTo("0J/RgNC40LLQtdGC");
            assertThat(delivered.get("sender").asText()).isEqualTo(owner.publicKey());
            // Дедлайн нужен вкладке, чтобы гасить реплику и убирать её из ленты: сервер
            // её уже не отдаёт, а открытая вкладка иначе показывает вечно.
            assertThat(delivered.get("expiresAt").asLong()).isGreaterThan(System.currentTimeMillis());
        }
    }

    @Test
    void clampsTtlAskedBySender() throws Exception {
        // Сервер зажимает присланное значение: потолок комнаты нельзя обойти кадром.
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":3600}")
                    .get("room").asText();
            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();

            owner.send("{\"op\":\"send\",\"room\":\"" + roomId
                    + "\",\"ciphertext\":\"AQID\",\"ttl\":999999}");

            long deadline = owner.awaitOp("msg").get("expiresAt").asLong();
            assertThat(deadline).isLessThanOrEqualTo(System.currentTimeMillis() + 3600 * 1000 + 5000);
        }
    }

    @Test
    void refusesMessageOverSizeLimit() throws Exception {
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();

            String huge = Base64.getEncoder().encodeToString(new byte[70 * 1024]);
            JsonNode answer = owner.request(
                    "{\"op\":\"send\",\"room\":\"" + roomId + "\",\"ciphertext\":\"" + huge + "\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void doesNotCarryMessageToOtherRoom() throws Exception {
        try (Client owner = new Client(); Client outsider = new Client()) {
            String mine = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            String theirs = outsider.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();

            owner.send("{\"op\":\"enter\",\"room\":\"" + mine + "\",\"since\":0}");
            outsider.send("{\"op\":\"enter\",\"room\":\"" + theirs + "\",\"since\":0}");
            owner.drain();
            outsider.drain();

            owner.send("{\"op\":\"send\",\"room\":\"" + mine + "\",\"ciphertext\":\"0YfRg9C20L7QtQ==\"}");
            owner.awaitOp("msg");

            assertThat(outsider.poll()).as("в чужую комнату ничего не уходит").isNull();
        }
    }

    @Test
    void refusesToEnterRoomWithoutMembership() throws Exception {
        try (Client owner = new Client(); Client stranger = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();

            JsonNode answer = stranger.request("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void refusesToSendIntoRoomWithoutMembership() throws Exception {
        try (Client owner = new Client(); Client stranger = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();

            JsonNode answer = stranger.request(
                    "{\"op\":\"send\",\"room\":\"" + roomId + "\",\"ciphertext\":\"0YfRg9C20L7QtQ==\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void handsUploadTokenToMember() throws Exception {
        // Пропуск на загрузку выдаётся по сокету, а тело едет отдельным HTTP-запросом:
        // двадцать мегабайт по каналу сообщений забили бы ленту всем участникам.
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();

            JsonNode answer = owner.request(
                    "{\"op\":\"upload\",\"room\":\"" + roomId + "\",\"size\":1000,\"ttl\":3600}");

            assertThat(answer.get("op").asText()).isEqualTo("upload-ready");
            assertThat(answer.get("id").asText()).isNotBlank();
            assertThat(answer.get("token").asText()).isNotBlank();
            assertThat(answer.get("expiresAt").asLong()).isGreaterThan(System.currentTimeMillis());
        }
    }

    @Test
    void refusesUploadTokenToStranger() throws Exception {
        try (Client owner = new Client(); Client stranger = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            assertThat(owner.request("{\"op\":\"upload\",\"room\":\"" + roomId + "\",\"size\":1000}")
                    .get("op").asText())
                    .as("участнику пропуск выдаётся — значит отказ ниже про членство")
                    .isEqualTo("upload-ready");

            JsonNode answer = stranger.request(
                    "{\"op\":\"upload\",\"room\":\"" + roomId + "\",\"size\":1000}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void refusesUploadTokenForOversizedFile() throws Exception {
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();

            assertThat(owner.request("{\"op\":\"upload\",\"room\":\"" + roomId + "\",\"size\":1000}")
                    .get("op").asText())
                    .as("посильный размер проходит — значит отказ ниже про размер")
                    .isEqualTo("upload-ready");

            JsonNode answer = owner.request("{\"op\":\"upload\",\"room\":\"" + roomId
                    + "\",\"size\":999999999}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void carriesDirectMessageToEveryMemberOfTheRoom() throws Exception {
        // Рассылка идёт всем, а не получателю: кому именно адресовано, сервер не знает
        // и знать не должен. Разбирается с этим клиент, разворачивая только свой слот.
        try (Client owner = new Client(); Client guest = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            inviteAndRedeem(owner, roomId, "для-гостя", guest);

            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            guest.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();
            guest.drain();

            String envelopes = envelopes();
            owner.send("{\"op\":\"dm\",\"room\":\"" + roomId + "\",\"ciphertext\":\"0JvQuNGH0L3QvtC1\""
                    + ",\"envelopes\":\"" + envelopes + "\"}");

            JsonNode delivered = guest.awaitOp("direct");
            assertThat(delivered.get("ciphertext").asText()).isEqualTo("0JvQuNGH0L3QvtC1");
            assertThat(delivered.get("envelopes").asText()).isEqualTo(envelopes);
            assertThat(delivered.has("sender")).as("отправителя в кадре нет").isFalse();
            assertThat(delivered.has("recipient")).as("получателя в кадре нет").isFalse();
            assertThat(delivered.get("expiresAt").asLong()).isGreaterThan(System.currentTimeMillis());
        }
    }

    @Test
    void refusesDirectMessageWithIncompleteDeck() throws Exception {
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();

            assertThat(owner.request("{\"op\":\"dm\",\"room\":\"" + roomId
                    + "\",\"ciphertext\":\"AQID\",\"envelopes\":\"" + envelopes() + "\"}")
                    .get("op").asText())
                    .as("полная колода принимается — значит отказ ниже про число слотов")
                    .isEqualTo("direct");

            String short51 = Base64.getEncoder().encodeToString(new byte[51 * 80]);
            JsonNode answer = owner.request("{\"op\":\"dm\",\"room\":\"" + roomId
                    + "\",\"ciphertext\":\"AQID\",\"envelopes\":\"" + short51 + "\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void refusesDirectMessageWhereRoomForbidsIt() throws Exception {
        try (Client owner = new Client()) {
            String quiet = owner.request(
                    "{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400,\"directAllowed\":false}")
                    .get("room").asText();
            owner.send("{\"op\":\"enter\",\"room\":\"" + quiet + "\",\"since\":0}");
            owner.drain();

            String loud = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.send("{\"op\":\"enter\",\"room\":\"" + loud + "\",\"since\":0}");
            owner.drain();
            assertThat(owner.request("{\"op\":\"dm\",\"room\":\"" + loud
                    + "\",\"ciphertext\":\"AQID\",\"envelopes\":\"" + envelopes() + "\"}")
                    .get("op").asText())
                    .as("в обычной комнате личное проходит — значит отказ ниже про настройку")
                    .isEqualTo("direct");

            JsonNode answer = owner.request("{\"op\":\"dm\",\"room\":\"" + quiet
                    + "\",\"ciphertext\":\"AQID\",\"envelopes\":\"" + envelopes() + "\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void refusesDirectMessageToStranger() throws Exception {
        try (Client owner = new Client(); Client stranger = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();

            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();
            assertThat(owner.request("{\"op\":\"dm\",\"room\":\"" + roomId
                    + "\",\"ciphertext\":\"AQID\",\"envelopes\":\"" + envelopes() + "\"}")
                    .get("op").asText())
                    .as("участнику проходит — значит отказ ниже про членство")
                    .isEqualTo("direct");

            JsonNode answer = stranger.request("{\"op\":\"dm\",\"room\":\"" + roomId
                    + "\",\"ciphertext\":\"AQID\",\"envelopes\":\"" + envelopes() + "\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void publishesKeyOfPrivateCorrespondenceInTheRoster() throws Exception {
        // Ключ переписки свой на каждую эпоху и публикуется владельцем: обёртку ключа
        // комнаты кладёт другой участник, а этот ключ человек объявляет сам.
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();

            owner.send("{\"op\":\"dm-key\",\"room\":\"" + roomId + "\",\"epoch\":1,\"key\":\"ключ-переписки\"}");
            JsonNode roster = owner.request("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");

            assertThat(roster.get("members")).anySatisfy(seat -> {
                assertThat(seat.get("device").asText()).isEqualTo(owner.publicKey());
                assertThat(seat.get("dm").asText()).isEqualTo("ключ-переписки");
            });
        }
    }

    @Test
    void catchesUpOnDirectMessagesAfterReconnect() throws Exception {
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();
            owner.send("{\"op\":\"dm\",\"room\":\"" + roomId
                    + "\",\"ciphertext\":\"AQID\",\"envelopes\":\"" + envelopes() + "\"}");
            owner.awaitOp("direct");

            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0,\"sinceDirect\":0}");

            assertThat(owner.awaitOp("direct").get("ciphertext").asText()).isEqualTo("AQID");
        }
    }

    private static String envelopes() {
        return Base64.getEncoder().encodeToString(new byte[52 * 80]);
    }

    @Test
    void survivesRedeemingAnOwnerInviteOnTheSameSession() throws Exception {
        // Owner-инвайт идёт без комнаты: рассылать состав некому. Раньше сервер всё равно
        // лез в реестр с room_id = null и ронял сессию — вкладка молча переподключалась,
        // поэтому наружу это выглядело исправной работой.
        try (Client newcomer = new Client(false)) {
            jdbc.update("""
                    INSERT INTO invite (token_hash, room_id, role, created_by, wrapped_key, expires_at, uses_left)
                    VALUES (?, NULL, 'owner', NULL, x'00', ?, 1)
                    """, InviteHash.of("owner-пропуск"), System.currentTimeMillis() + 3_600_000);

            JsonNode redeemed = newcomer.request("{\"op\":\"redeem\",\"token\":\"owner-пропуск\"}");
            assertThat(redeemed.get("op").asText()).isEqualTo("redeemed");

            // Та же сессия обязана продолжать работать: если её закрыли, ответа не будет.
            JsonNode room = newcomer.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}");
            assertThat(room.get("op").asText()).isEqualTo("room");
        }
    }

    @Test
    void storesOneTimeNoteForMember() throws Exception {
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();

            JsonNode answer = owner.request("{\"op\":\"once\",\"room\":\"" + roomId
                    + "\",\"tokenHash\":\"хэш-записки\",\"ciphertext\":\"0JfQsNC/0LjRgdC60LA=\",\"ttl\":3600}");

            assertThat(answer.get("op").asText()).isEqualTo("once-stored");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM onetime WHERE token_hash = 'хэш-записки'", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void refusesOneTimeNoteToStranger() throws Exception {
        // Записка привязана к комнате: без этого её не заденет ни удаление комнаты,
        // ни исключение автора.
        try (Client owner = new Client(); Client stranger = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            assertThat(owner.request("{\"op\":\"once\",\"room\":\"" + roomId
                    + "\",\"tokenHash\":\"своя\",\"ciphertext\":\"AQID\"}").get("op").asText())
                    .as("участнику записка сохраняется — значит отказ ниже про членство")
                    .isEqualTo("once-stored");

            JsonNode answer = stranger.request("{\"op\":\"once\",\"room\":\"" + roomId
                    + "\",\"tokenHash\":\"чужая\",\"ciphertext\":\"AQID\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM onetime WHERE token_hash = 'чужая'", Integer.class)).isZero();
        }
    }

    @Test
    void handsWrappedKeyOnlyToItsOwner() throws Exception {
        try (Client owner = new Client(); Client stranger = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.send("{\"op\":\"wrap\",\"room\":\"" + roomId + "\",\"epoch\":1,\"device\":\""
                    + owner.publicKey() + "\",\"wrapped\":\"AQID\"}");
            owner.drain();

            JsonNode mine = owner.request("{\"op\":\"key\",\"room\":\"" + roomId + "\",\"epoch\":1}");
            assertThat(mine.get("wrapped").asText()).isEqualTo("AQID");

            // Чужая обёртка бесполезна и без того — она под другим X25519, — но и отдавать
            // её незачем: это лишний сигнал о составе комнаты.
            JsonNode theirs = stranger.request("{\"op\":\"key\",\"room\":\"" + roomId + "\",\"epoch\":1}");
            assertThat(theirs.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void reportsWhoStillNeedsWrapperOfCurrentEpoch() throws Exception {
        try (Client owner = new Client(); Client guest = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            inviteAndRedeem(owner, roomId, "для-обёртки", guest);

            JsonNode room = owner.request("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");

            assertThat(room.get("needWrap")).isNotNull();
            assertThat(room.get("needWrap").findValuesAsText("device"))
                    .contains(owner.publicKey(), guest.publicKey());
        }
    }

    @Test
    void remembersEncryptionKeyOfDevice() throws Exception {
        try (Client owner = new Client()) {
            owner.send("{\"op\":\"hello\",\"box\":\"" + owner.publicKey() + "\"}");
            owner.drain();

            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            JsonNode room = owner.request("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");

            // Без X25519 участника обернуть под него ключ комнаты нечем.
            assertThat(room.get("members").findValuesAsText("box")).contains(owner.publicKey());
        }
    }

    /** Выпустить приглашение и провести по нему гостя: теперь только так. */
    private void inviteAndRedeem(Client host, String roomId, String token, Client guest) throws Exception {
        host.send("{\"op\":\"invite\",\"room\":\"" + roomId + "\",\"tokenHash\":\"" + hashOf(token)
                + "\",\"role\":\"member\",\"ttl\":3600,\"uses\":1,\"wrapped\":\"AQID\"}");
        host.drain();
        assertThat(guest.request("{\"op\":\"redeem\",\"token\":\"" + token + "\"}").get("op").asText())
                .isEqualTo("redeemed");
    }

    private static String hashOf(String token) throws Exception {
        return ENCODER.encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes()));
    }

    @Test
    void noLongerLetsAnyoneInByRoomNumber() throws Exception {
        // Вход по номеру комнаты закрыт: попасть внутрь можно только по приглашению.
        try (Client owner = new Client(); Client stranger = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();

            JsonNode answer = stranger.request("{\"op\":\"join\",\"room\":\"" + roomId + "\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
            assertThat(stranger.request("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}")
                    .get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void letsNewcomerInByInvite() throws Exception {
        try (Client owner = new Client(); Client newcomer = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.send("{\"op\":\"invite\",\"room\":\"" + roomId + "\",\"tokenHash\":\"" + hashOf("пригласительный")
                    + "\",\"role\":\"member\",\"ttl\":3600,\"uses\":1,\"wrapped\":\"AQID\"}");
            owner.drain();

            JsonNode answer = newcomer.request("{\"op\":\"redeem\",\"token\":\"пригласительный\"}");

            assertThat(answer.get("op").asText()).isEqualTo("redeemed");
            assertThat(answer.get("room").asText()).isEqualTo(roomId);
            assertThat(answer.get("wrapped").asText()).isEqualTo("AQID");
        }
    }

    @Test
    void refusesSpentInviteWithoutSayingWhy() throws Exception {
        try (Client owner = new Client(); Client first = new Client(); Client second = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.send("{\"op\":\"invite\",\"room\":\"" + roomId + "\",\"tokenHash\":\"" + hashOf("одноразовый")
                    + "\",\"role\":\"member\",\"ttl\":3600,\"uses\":1,\"wrapped\":\"AQID\"}");
            owner.drain();
            // Сначала убеждаемся, что рабочий путь есть: иначе тест зелен и когда
            // погашения не существует вовсе.
            assertThat(first.request("{\"op\":\"redeem\",\"token\":\"одноразовый\"}").get("op").asText())
                    .isEqualTo("redeemed");

            JsonNode spent = second.request("{\"op\":\"redeem\",\"token\":\"одноразовый\"}");
            JsonNode never = second.request("{\"op\":\"redeem\",\"token\":\"такого-не-было\"}");

            // Ответы обязаны совпасть дословно: по разнице видно, какой инвайт существовал.
            assertThat(spent.get("op").asText()).isEqualTo("error");
            assertThat(spent.get("reason").asText()).isEqualTo(never.get("reason").asText());
        }
    }

    @Test
    void letsOnlyOwnerAndAdminIssueInvites() throws Exception {
        try (Client owner = new Client(); Client member = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.send("{\"op\":\"invite\",\"room\":\"" + roomId + "\",\"tokenHash\":\"" + hashOf("для-участника")
                    + "\",\"role\":\"member\",\"ttl\":3600,\"uses\":1,\"wrapped\":\"AQID\"}");
            owner.drain();
            assertThat(member.request("{\"op\":\"redeem\",\"token\":\"для-участника\"}").get("op").asText())
                    .isEqualTo("redeemed");

            JsonNode answer = member.request("{\"op\":\"invite\",\"room\":\"" + roomId + "\",\"tokenHash\":\""
                    + hashOf("самозваный") + "\",\"role\":\"member\",\"ttl\":3600,\"uses\":1,\"wrapped\":\"AQID\"}");

            assertThat(answer.get("op").asText()).isEqualTo("error");
        }
    }

    @Test
    void revokesInviteOnOwnerCommand() throws Exception {
        try (Client owner = new Client(); Client late = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            String hash = hashOf("отзываемый");
            owner.send("{\"op\":\"invite\",\"room\":\"" + roomId + "\",\"tokenHash\":\"" + hash
                    + "\",\"role\":\"member\",\"ttl\":3600,\"uses\":5,\"wrapped\":\"AQID\"}");
            owner.drain();

            // До отзыва ссылка работает — иначе тест ничего не проверяет.
            try (Client early = new Client()) {
                assertThat(early.request("{\"op\":\"redeem\",\"token\":\"отзываемый\"}").get("op").asText())
                        .isEqualTo("redeemed");
            }

            owner.send("{\"op\":\"revoke\",\"room\":\"" + roomId + "\",\"tokenHash\":\"" + hash + "\"}");
            owner.drain();

            assertThat(late.request("{\"op\":\"redeem\",\"token\":\"отзываемый\"}").get("op").asText())
                    .isEqualTo("error");
        }
    }

    @Test
    void tellsRoomAboutNewcomer() throws Exception {
        // Карта — это имя участника в комнате. Без рассылки состава сидящие видят у новичка
        // отпечаток вместо карты и неверный счётчик мест, пока не переоткроют вкладку.
        try (Client owner = new Client(); Client guest = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.request("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();

            inviteAndRedeem(owner, roomId, "новичку", guest);

            JsonNode roster = owner.awaitOp("room");
            assertThat(roster.get("seatsTaken").asInt()).isEqualTo(2);
            assertThat(roster.get("members").findValuesAsText("device")).contains(guest.publicKey());
        }
    }

    @Test
    void echoesMessageBackToItsSender() throws Exception {
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            owner.drain();

            owner.send("{\"op\":\"send\",\"room\":\"" + roomId + "\",\"ciphertext\":\"0YHQstC+0ZE=\"}");

            // Своя реплика рисуется по подтверждению с id, а не сразу: иначе после
            // реконнекта она задвоится с тем, что придёт из догрузки.
            assertThat(owner.awaitOp("msg").get("ciphertext").asText()).isEqualTo("0YHQstC+0ZE=");
        }
    }

    @Test
    void deliversBacklogAfterReconnect() throws Exception {
        String roomId;
        KeyPair device = Ed25519Keys.newKeyPair();
        try (Client first = new Client(device)) {
            roomId = first.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            first.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            first.drain();
            first.send("{\"op\":\"send\",\"room\":\"" + roomId + "\",\"ciphertext\":\"0YHRgtCw0YDQvtC1\"}");
            first.awaitOp("msg");
        }

        try (Client reconnected = new Client(device)) {
            reconnected.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");

            assertThat(reconnected.awaitOp("msg").get("ciphertext").asText()).isEqualTo("0YHRgtCw0YDQvtC1");
        }
    }

    @Test
    void skipsBacklogAlreadySeen() throws Exception {
        String roomId;
        long lastSeen;
        KeyPair device = Ed25519Keys.newKeyPair();
        try (Client first = new Client(device)) {
            roomId = first.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            first.send("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            first.drain();
            first.send("{\"op\":\"send\",\"room\":\"" + roomId + "\",\"ciphertext\":\"0YHRgtCw0YDQvtC1\"}");
            lastSeen = first.awaitOp("msg").get("id").asLong();
        }

        try (Client reconnected = new Client(device)) {
            reconnected.request("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":" + lastSeen + "}");

            assertThat(reconnected.poll()).as("прочитанное второй раз не приходит").isNull();
        }
    }

    @Test
    void forgetsSessionAfterClose() throws Exception {
        try (Client owner = new Client()) {
            String roomId = owner.request("{\"op\":\"create\",\"defaultTtl\":3600,\"maxTtl\":86400}")
                    .get("room").asText();
            owner.request("{\"op\":\"enter\",\"room\":\"" + roomId + "\",\"since\":0}");
            await().atMost(Duration.ofSeconds(5)).until(() -> registry.sessions(roomId).size() == 1);

            owner.close();

            // Осиротевшая сессия в реестре означала бы рассылку в закрытый сокет и комнату,
            // которая никогда не опустеет.
            await().atMost(Duration.ofSeconds(5)).until(() -> registry.sessions(roomId).isEmpty());
        }
    }

    /** Клиент с настоящим входом по подписи. */
    private final class Client extends TextWebSocketHandler implements AutoCloseable {

        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final WebSocketSession session;
        private final String publicKey;

        /** По умолчанию устройство сразу пущено на сервер: комнаты нужны почти всем тестам. */
        private Client() throws Exception {
            this(Ed25519Keys.newKeyPair(), true);
        }

        /** Клиент, которого никто не звал. */
        private Client(boolean admitted) throws Exception {
            this(Ed25519Keys.newKeyPair(), admitted);
        }

        /** Реконнект тем же устройством. */
        private Client(KeyPair device) throws Exception {
            this(device, true);
        }

        private Client(KeyPair device, boolean admitted) throws Exception {
            this.publicKey = ENCODER.encodeToString(Ed25519Keys.rawPublicKey(device.getPublic()));

            @SuppressWarnings("unchecked")
            Map<String, String> challenge = rest.getForObject("/api/challenge", Map.class);
            String nonce = challenge.get("nonce");
            String signature = ENCODER.encodeToString(
                    Ed25519Keys.sign(Base64.getUrlDecoder().decode(nonce), device.getPrivate()));

            this.session = new StandardWebSocketClient().execute(this, null,
                    URI.create("ws://localhost:" + port + "/ws?nonce=" + nonce
                            + "&device=" + publicKey + "&signature=" + signature))
                    .get(5, TimeUnit.SECONDS);

            // Только после апгрейда: до него устройства в базе нет, и внешний ключ
            // instance_owner не даст выдать пропуск несуществующему.
            if (admitted) {
                admit(publicKey);
            }
        }

        String publicKey() {
            return publicKey;
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

        JsonNode awaitOp(String op) throws Exception {
            for (int i = 0; i < 10; i++) {
                JsonNode frame = next();
                if (op.equals(frame.get("op").asText())) {
                    return frame;
                }
            }
            throw new AssertionError("не дождались кадра " + op);
        }

        String poll() throws InterruptedException {
            return received.poll(1, TimeUnit.SECONDS);
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
