package org.anteroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.anteroom.auth.ChallengeService;
import org.anteroom.auth.Ed25519Keys;
import org.anteroom.file.BlobStore;
import org.anteroom.file.FileService;
import org.anteroom.file.FileTooLargeException;
import org.anteroom.file.StoredFile;
import org.anteroom.file.Upload;
import org.anteroom.invite.InviteHash;
import org.anteroom.invite.InviteService;
import org.anteroom.invite.OwnerBootstrap;
import org.anteroom.invite.Redemption;
import org.anteroom.message.DirectService;
import org.anteroom.message.MessageService;
import org.anteroom.message.OneTimeService;
import org.anteroom.room.DeckSpentException;
import org.anteroom.room.KeyEpochService;
import org.anteroom.room.Room;
import org.anteroom.room.RoomService;
import org.anteroom.ttl.SweeperJob;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Приёмочный прогон по безопасности, фаза 13.
 *
 * <p>Четырнадцать пунктов чеклиста как исполняемые проверки, а не как список для глаз.
 * Прогоняется целиком перед релизом: выборочный прогон ничего не значит — половина
 * пунктов ловит ровно те ошибки, которые снаружи выглядят исправной работой.
 *
 * <p>Часы подменены управляемыми: сроки жизни здесь проверяются переводом времени,
 * а не ожиданием.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.data-dir=build/test-data-acceptance",
                "app.challenge.per-ip-limit=10000",
                "app.work.bits=0",
                "app.limit.create-per-hour=100000",
                "app.limit.create-per-hour-ip=100000",
                "app.limit.invite-per-hour=100000",
                "app.limit.write-per-minute=100000",
                // Часы подменяются одноимённым бином: сроки жизни проверяются переводом
                // времени, а не ожиданием.
                "spring.main.allow-bean-definition-overriding=true" })
class AcceptanceTest {

    private static final Path DATA_DIR = Path.of("build/test-data-acceptance");
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Заметные строки: если такая утекла в журнал или в базу, её видно сразу. */
    private static final String PLAINTEXT = "ОТКРЫТЫЙ-ТЕКСТ-НЕ-ДОЛЖЕН-ПОКИДАТЬ-БРАУЗЕР";
    private static final String INVITE_TOKEN = "СЫРОЙ-ТОКЕН-ПРИГЛАШЕНИЯ";
    private static final String ONCE_TOKEN = "СЫРОЙ-ТОКЕН-ЗАПИСКИ";
    private static final String FILE_NAME = "ИМЯ-ФАЙЛА-МЕТАДАННЫЕ.txt";

    private static ListAppender<ILoggingEvent> journal;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private RoomService rooms;
    @Autowired
    private KeyEpochService epochs;
    @Autowired
    private InviteService invites;
    @Autowired
    private MessageService messages;
    @Autowired
    private OneTimeService onetime;
    @Autowired
    private DirectService direct;
    @Autowired
    private FileService files;
    @Autowired
    private BlobStore blobs;
    @Autowired
    private SweeperJob sweeper;
    @Autowired
    private ChallengeService challenges;
    @Autowired
    private OwnerBootstrap bootstrap;

    @TestConfiguration
    static class ControlledTime {
        @Bean
        @Primary
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-08-06T12:00:00Z"));
        }
    }

    @BeforeAll
    static void startFromEmptyDirectoryAndWatchTheJournal() throws IOException {
        if (Files.exists(DATA_DIR)) {
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

    }

    /**
     * Вешает перехватчик журнала на корневой логгер.
     *
     * <p>Не в {@code @BeforeAll}: к тому моменту Spring ещё переинициализирует logback,
     * и повешенный раньше аппендер до операций не доживает — а выглядит это как «в журнале
     * ничего нет», то есть как успешно пройденная проверка.
     *
     * <p>Уровень DEBUG: проверка «в логах нет секретов» должна смотреть на всё, что
     * приложение вообще способно написать, а не на INFO и выше.
     *
     * <p>Строки самого старта сюда не попадают — их проверяют живым прогоном.
     */
    private void watchTheJournal() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.DEBUG);
        journal = new ListAppender<>();
        journal.start();
        root.addAppender(journal);
    }

    private String journalText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : new ArrayList<>(journal.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private String databaseDump() {
        StringBuilder dump = new StringBuilder();
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                String.class);
        for (String table : tables) {
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + table)) {
                for (Map.Entry<String, Object> column : row.entrySet()) {
                    Object value = column.getValue();
                    dump.append(table).append('.').append(column.getKey()).append('=');
                    dump.append(value instanceof byte[] bytes ? new String(bytes) : String.valueOf(value));
                    dump.append('\n');
                }
            }
        }
        return dump.toString();
    }

    /**
     * Заводит устройство в базе и возвращает его ключ.
     *
     * <p>Внешний ключ {@code member.pubkey_sign → device} не даст посадить в комнату
     * того, кого сервер никогда не видел, — и это ровно то, ради чего включён
     * {@code foreign_keys=true}.
     */
    private String dev(String name) {
        jdbc.update("INSERT OR IGNORE INTO device (pubkey_sign, pubkey_box, created_at) VALUES (?, '', ?)",
                name, clock.millis());
        return name;
    }

    private Room room(long ttl) {
        String id = rooms.create(dev("устройство-хозяин"), ttl, ttl, true);
        return rooms.find(id);
    }

    // ---- 1 ----------------------------------------------------------------

    @Test
    @DisplayName("1. В журнале за прогон нет ни токенов, ни ключей, ни шифротекстов")
    void journalKeepsNoSecrets() throws Exception {
        watchTheJournal();

        Room room = room(3600);
        invites.create(room.id(), "устройство-хозяин", InviteHash.of(INVITE_TOKEN),
                "member", 3600, 1, "обёртка-ключа-комнаты".getBytes());
        invites.redeem(INVITE_TOKEN, dev("устройство-гость"));
        messages.save(room, "устройство-хозяин", PLAINTEXT.getBytes(), null);
        onetime.create(room, InviteHash.of(ONCE_TOKEN), PLAINTEXT.getBytes(), null);
        sweeper.sweep();

        // Ловушку надо проверить прежде, чем ей верить: пустой перехватчик пропустил бы
        // любой секрет и выглядел бы при этом совершенно нормально.
        LoggerFactory.getLogger(AcceptanceTest.class).info("маркер приёмочного прогона");

        String text = journalText();

        assertThat(text)
                .as("перехватчик журнала работает — иначе проверки ниже не значат ничего")
                .contains("маркер приёмочного прогона");
        assertThat(text).doesNotContain(INVITE_TOKEN);
        assertThat(text).doesNotContain(ONCE_TOKEN);
        assertThat(text).doesNotContain(PLAINTEXT);
        assertThat(text).doesNotContain("обёртка-ключа-комнаты");
    }

    // ---- 2 ----------------------------------------------------------------

    @Test
    @DisplayName("2. В базе только шифротексты и хэши: ни открытого текста, ни сырых токенов")
    void databaseKeepsOnlyCiphertextAndHashes() {
        Room room = room(3600);
        // Свои токены: база в этом прогоне общая, и на чужом хэше упёрлись бы в PRIMARY KEY.
        String invite = INVITE_TOKEN + "-для-дампа";
        String once = ONCE_TOKEN + "-для-дампа";
        invites.create(room.id(), dev("устройство-хозяин"), InviteHash.of(invite),
                "member", 3600, 1, "обёртка".getBytes());
        onetime.create(room, InviteHash.of(once), "шифротекст".getBytes(), null);

        String dump = databaseDump();

        assertThat(dump)
                .as("дамп непустой — иначе проверка ниже проходит на пустоте")
                .contains(InviteHash.of(invite));
        assertThat(dump).as("сырого токена приглашения в базе нет").doesNotContain(invite);
        assertThat(dump).as("сырого токена записки в базе нет").doesNotContain(once);
    }

    // ---- 3 ----------------------------------------------------------------

    @Test
    @DisplayName("3. Исключённый не получает ключа новой эпохи")
    void removedMemberGetsNoKeyOfTheNewEpoch() {
        Room room = room(3600);
        rooms.join(room.id(), dev("устройство-гость"), "member", "устройство-хозяин");
        epochs.storeWrappedKey(room.id(), dev("устройство-гость"), 1, "обёртка первой эпохи".getBytes());

        rooms.remove(room.id(), dev("устройство-гость"));
        int epoch = epochs.rotate(room.id());

        assertThat(epochs.wrappedKey(room.id(), dev("устройство-гость"), 1))
                .as("старую историю он расшифрует — перешифровать прошлое невозможно")
                .isNotNull();
        assertThat(epochs.wrappedKey(room.id(), dev("устройство-гость"), epoch))
                .as("ключа новой эпохи у него нет")
                .isNull();
        assertThat(epochs.membersWithoutWrapper(room.id(), epoch))
                .as("и выписывать его никто не собирается")
                .doesNotContain(dev("устройство-гость"));
    }

    // ---- 4 ----------------------------------------------------------------

    @Test
    @DisplayName("4. Протухшее недоступно и в окне между тиками sweeper'а")
    void expiredIsHiddenBetweenSweeps() {
        Room room = room(60);
        messages.save(room, "устройство-хозяин", "минутка".getBytes(), 60L);

        clock.advance(Duration.ofSeconds(61));

        assertThat(messages.since(room.id(), 0)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE room_id = ?",
                Integer.class, room.id()))
                .as("строка ещё в базе: наружу её не пускает фильтр при чтении, а не уборка")
                .isEqualTo(1);
    }

    // ---- 5 ----------------------------------------------------------------

    @Test
    @DisplayName("5. Сожжённая, протухшая и несуществующая записка отвечают одинаково")
    void burnedExpiredAndUnknownLookTheSame() {
        Room room = room(3600);
        onetime.create(room, InviteHash.of("сожгу"), "шифротекст".getBytes(), null);
        onetime.create(room, InviteHash.of("протухнет"), "шифротекст".getBytes(), 60L);

        assertThat(onetime.burn("сожгу")).as("живая записка отдаётся").isNotNull();
        clock.advance(Duration.ofSeconds(61));

        assertThat(onetime.burn("сожгу")).isNull();
        assertThat(onetime.burn("протухнет")).isNull();
        assertThat(onetime.burn("такой не выдавали")).isNull();
    }

    // ---- 6 ----------------------------------------------------------------

    @Test
    @DisplayName("6. Повторное предъявление вызова входа отбивается")
    void challengeCannotBeReplayed() throws Exception {
        KeyPair device = Ed25519Keys.newKeyPair();
        String publicKey = URL64.encodeToString(Ed25519Keys.rawPublicKey(device.getPublic()));

        @SuppressWarnings("unchecked")
        Map<String, String> challenge = rest.getForObject("/api/challenge", Map.class);
        String nonce = challenge.get("nonce");
        String signature = URL64.encodeToString(
                Ed25519Keys.sign(Base64.getUrlDecoder().decode(nonce), device.getPrivate()));

        try (Socket first = new Socket(nonce, publicKey, signature)) {
            assertThat(first.opened()).as("первый вход проходит").isTrue();
        }
        try (Socket replay = new Socket(nonce, publicKey, signature)) {
            assertThat(replay.opened()).as("тот же вызов второй раз не проходит").isFalse();
        }
    }

    // ---- 7 ----------------------------------------------------------------

    @Test
    @DisplayName("7. Имени файла и MIME-типа нет ни в схеме, ни в базе, ни в журнале")
    void fileNameAndTypeNeverReachTheServer() {
        watchTheJournal();

        Room room = room(3600);
        Upload upload = files.issue(room, "устройство-хозяин", 32, null);
        // Имя и тип едут внутри сообщения, зашифрованного ключом комнаты. Сюда они
        // не попадают даже случайно: их некуда положить.
        files.accept(upload.token(), new java.io.ByteArrayInputStream(new byte[32]), 32);

        Set<String> columns = new HashSet<>(jdbc.queryForList("SELECT * FROM file").get(0).keySet());

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "room_id", "uploader", "size_bytes", "created_at", "expires_at");
        assertThat(databaseDump()).doesNotContain(FILE_NAME);
        assertThat(journalText()).doesNotContain(FILE_NAME);
    }

    // ---- 8 ----------------------------------------------------------------

    @Test
    @DisplayName("8. Присланный клиентом срок больше потолка зажимается сервером")
    void serverClampsTheTtlTheClientAsksFor() {
        Room room = room(3600);
        long deadline = messages.save(room, "устройство-хозяин", "долгожитель".getBytes(), 999_999L)
                .expiresAt();

        assertThat(deadline).isEqualTo(clock.millis() + 3600 * 1000);
    }

    // ---- 9 ----------------------------------------------------------------

    @Test
    @DisplayName("9. Карта выбывшего не возвращается, а 53-й вход отклоняется как конец колоды")
    void retiredCardStaysRetiredAndTheDeckEnds() {
        Room room = room(3600);
        int retired = rooms.join(room.id(), dev("устройство-выбывший"), "member", null);
        rooms.remove(room.id(), dev("устройство-выбывший"));

        Set<Integer> dealt = new HashSet<>();
        dealt.add(retired);
        // Хозяин занял место при создании комнаты, выбывший — второе. Добираем колоду.
        for (int i = 0; i < 50; i++) {
            assertThat(dealt.add(rooms.join(room.id(), dev("устройство-" + i), "member", null)))
                    .as("карта " + i + " не повторилась")
                    .isTrue();
        }

        assertThatThrownBy(() -> rooms.join(room.id(), dev("пятьдесят-третий"), "member", null))
                .as("колода кончилась — это конец жизни комнаты, а не ошибка выдачи")
                .isInstanceOf(DeckSpentException.class);
    }

    // ---- 10 ---------------------------------------------------------------

    @Test
    @DisplayName("10. После срока не осталось ни строки в file, ни блоба на диске")
    void expiredFileLeavesNeitherRowNorBlob() throws IOException {
        Room room = room(60);
        Upload upload = files.issue(room, "устройство-хозяин", 16, 60L);
        StoredFile stored = files.accept(upload.token(),
                new java.io.ByteArrayInputStream(new byte[16]), 16);
        assertThat(Files.exists(blobs.path(stored.id()))).isTrue();

        clock.advance(Duration.ofSeconds(61));
        sweeper.sweep();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM file WHERE id = ?",
                Integer.class, stored.id())).isZero();
        assertThat(Files.exists(blobs.path(stored.id())))
                .as("блоб уходит первым, строка следом — обратный порядок оставляет вечный мусор")
                .isFalse();
    }

    // ---- 11 ---------------------------------------------------------------

    @Test
    @DisplayName("11. Исчерпание квоты и перебор размера дают внятный отказ, а не пятисотую")
    void quotaAndSizeRefusalsAreReadable() {
        Room room = room(3600);

        assertThatThrownBy(() -> files.issue(room, "устройство-хозяин", files.maxBytes() + 1, null))
                .isInstanceOf(FileTooLargeException.class)
                .hasMessageContaining("вложение больше");

        // Тесная квота именно на комнату: база в этом прогоне общая, и дисковая
        // к этому моменту уже занята вложениями из других проверок.
        FileService tight = new FileService(jdbc, blobs, clock, 1024, 16, Long.MAX_VALUE / 2);
        tight.accept(tight.issue(room, "устройство-хозяин", 16, null).token(),
                new java.io.ByteArrayInputStream(new byte[16]), 16);

        assertThatThrownBy(() -> tight.issue(room, "устройство-хозяин", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("в комнате кончилось место");
    }

    // ---- 12 ---------------------------------------------------------------

    @Test
    @DisplayName("12. Погашенное приглашение больше не даёт ключа комнаты")
    void spentInviteHandsOutNoRoomKeyAgain() {
        Room room = room(3600);
        String token = "одноразовое-приглашение";
        invites.create(room.id(), "устройство-хозяин", InviteHash.of(token),
                "member", 3600, 1, "обёртка".getBytes());

        Redemption first = invites.redeem(token, dev("устройство-первый"));
        assertThat(first.accepted()).isTrue();
        assertThat(first.wrappedKey()).isNotNull();

        Redemption second = invites.redeem(token, dev("устройство-второй"));

        assertThat(second.accepted()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invite WHERE token_hash = ?",
                Integer.class, InviteHash.of(token)))
                .as("строка ушла вместе с обёрткой: сохранённая ссылка бесполезна")
                .isZero();
    }

    // ---- 13 ---------------------------------------------------------------

    @Test
    @DisplayName("13. В direct нет получателя: 52 слота одной длины, настоящий неотличим")
    void directHidesTheRecipient() {
        Room room = room(3600);
        byte[] envelopes = new byte[DirectService.ENVELOPES];
        // Настоящий конверт кладём в одно место, остальное — случайные байты, как это
        // делает клиент.
        new java.security.SecureRandom().nextBytes(envelopes);
        direct.save(room, "шифротекст".getBytes(), envelopes, null);

        Map<String, Object> row = jdbc.queryForList(
                "SELECT * FROM direct WHERE room_id = ?", room.id()).get(0);

        assertThat(row.keySet()).containsExactlyInAnyOrder(
                "id", "room_id", "ciphertext", "envelopes", "created_at", "expires_at");

        byte[] stored = (byte[]) row.get("envelopes");
        assertThat(stored.length).isEqualTo(DirectService.ENVELOPES);
        for (int card = 0; card < DirectService.DECK; card++) {
            byte[] slot = java.util.Arrays.copyOfRange(
                    stored, card * DirectService.SLOT, (card + 1) * DirectService.SLOT);
            assertThat(slot.length).as("слот " + card).isEqualTo(DirectService.SLOT);
            assertThat(slot).as("слот " + card + " не нулевой").isNotEqualTo(new byte[DirectService.SLOT]);
        }
    }

    // ---- 14 ---------------------------------------------------------------

    @Test
    @DisplayName("14. Рестарт не повторяет онбординг и не печатает owner-инвайт заново")
    void restartAsksForNoSecondOnboarding() {
        String roomId = room(3600).id();
        jdbc.update("INSERT OR IGNORE INTO instance_owner (pubkey_sign, granted_at) VALUES (?, ?)",
                "устройство-хозяин", clock.millis());

        // Модель рестарта: тот же обработчик события готовности на той же базе.
        assertThat(bootstrap.ensureOwnerInvite())
                .as("владелец есть — ссылку печатать не за чем")
                .isNull();
        assertThat(rooms.find(roomId)).as("комната на месте").isNotNull();
        assertThat(rooms.members(roomId)).as("участники на месте").isNotEmpty();
    }

    // ---- вспомогательное --------------------------------------------------

    /** Соединение ровно для того, чтобы узнать, пустил ли сервер. */
    private final class Socket extends TextWebSocketHandler implements AutoCloseable {

        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private WebSocketSession session;
        private final boolean opened;

        private Socket(String nonce, String device, String signature) throws Exception {
            boolean accepted;
            try {
                session = new StandardWebSocketClient().execute(this, null,
                        URI.create("ws://localhost:" + port + "/ws?nonce=" + nonce
                                + "&device=" + device + "&signature=" + signature))
                        .get(5, TimeUnit.SECONDS);
                accepted = true;
            } catch (Exception e) {
                accepted = false;
            }
            this.opened = accepted;
        }

        boolean opened() {
            return opened;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(message.getPayload());
        }

        @Override
        public void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
