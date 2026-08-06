package org.anteroom.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import org.anteroom.room.Room;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.data-dir=build/test-data-message")
class MessageServiceTest {

    private static final Path DATA_DIR = Path.of("build/test-data-message");
    private static final String ROOM = "room-a";
    private static final String OTHER_ROOM = "room-b";

    @Autowired
    private JdbcTemplate jdbc;

    private MutableClock clock;
    private MessageService messages;

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
    void setUp() {
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM room");
        for (String room : List.of(ROOM, OTHER_ROOM)) {
            jdbc.update("INSERT INTO room (id, created_at) VALUES (?, ?)", room, 0);
        }
        clock = new MutableClock(Instant.parse("2026-08-05T12:00:00Z"));
        messages = new MessageService(jdbc, clock);
    }

    @Test
    void returnsSavedMessagesInSendOrder() {
        messages.save(room(60, 60), "device-1", "первое".getBytes(), null);
        messages.save(room(60, 60), "device-2", "второе".getBytes(), null);

        assertThat(messages.since(ROOM, 0))
                .extracting(m -> new String(m.ciphertext()))
                .containsExactly("первое", "второе");
    }

    @Test
    void hidesExpiredMessageBeforeSweeperRuns() {
        // Гипотеза фазы 1: протухшее не отдаётся благодаря фильтру при чтении, а не потому
        // что sweeper успел. Между тиками сервер обязан молчать о просроченном.
        messages.save(room(60, 60), "device-1", "минутка".getBytes(), null);

        clock.advance(Duration.ofSeconds(61));

        assertThat(messages.since(ROOM, 0)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message", Integer.class))
                .as("строка ещё в базе — sweeper не отрабатывал")
                .isEqualTo(1);
    }

    @Test
    void keepsMessageUntilItsDeadline() {
        messages.save(room(60, 60), "device-1", "минутка".getBytes(), null);

        clock.advance(Duration.ofSeconds(59));

        assertThat(messages.since(ROOM, 0)).hasSize(1);
    }

    @Test
    void returnsOnlyMessagesNewerThanLastSeen() {
        long first = messages.save(room(60, 60), "device-1", "первое".getBytes(), null).id();
        messages.save(room(60, 60), "device-1", "второе".getBytes(), null);

        assertThat(messages.since(ROOM, first))
                .extracting(m -> new String(m.ciphertext()))
                .containsExactly("второе");
    }

    @Test
    void doesNotLeakBetweenRooms() {
        messages.save(otherRoom(), "device-1", "чужое".getBytes(), null);

        assertThat(messages.since(ROOM, 0)).isEmpty();
    }

    @Test
    void sweepDeletesExpiredAndKeepsLive() {
        messages.save(room(60, 60), "device-1", "старое".getBytes(), null);
        clock.advance(Duration.ofSeconds(61));
        messages.save(room(60, 60), "device-1", "свежее".getBytes(), null);

        int deleted = messages.sweepExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(messages.since(ROOM, 0))
                .extracting(m -> new String(m.ciphertext()))
                .containsExactly("свежее");
    }

    private static Room room(long defaultTtl, long maxTtl) {
        return new Room(ROOM, 1, defaultTtl, maxTtl, 1, 0);
    }

    private static Room otherRoom() {
        return new Room(OTHER_ROOM, 1, 60, 60, 1, 0);
    }

    @Test
    void clampsTtlToRoomCeiling() {
        // Значение приходит от клиента, поэтому верить ему нельзя: пятидневный потолок
        // держится низким сознательно, и обойти его присланным числом невозможно.
        messages.save(room(3600, 3600), "device-1", "долгожитель".getBytes(), 999_999L);

        clock.advance(Duration.ofSeconds(3601));

        assertThat(messages.since(ROOM, 0)).isEmpty();
    }

    @Test
    void clampsTtlToFloor() {
        messages.save(room(3600, 3600), "device-1", "мгновение".getBytes(), 1L);

        clock.advance(Duration.ofSeconds(59));

        assertThat(messages.since(ROOM, 0)).as("минута — пол, ниже сервер не опускает").hasSize(1);
    }

    @Test
    void honoursTtlInsideAllowedRange() {
        messages.save(room(172_800, 432_000), "device-1", "на час".getBytes(), 3600L);

        clock.advance(Duration.ofSeconds(3599));
        assertThat(messages.since(ROOM, 0)).hasSize(1);

        clock.advance(Duration.ofSeconds(2));
        assertThat(messages.since(ROOM, 0)).isEmpty();
    }

    @Test
    void fallsBackToRoomDefaultWhenSenderSaysNothing() {
        messages.save(room(3600, 432_000), "device-1", "молча".getBytes(), null);

        clock.advance(Duration.ofSeconds(3601));

        assertThat(messages.since(ROOM, 0)).isEmpty();
    }

    @Test
    void refusesCiphertextOverLimit() {
        // 64 КБ — потолок реплики. Без него один кадр забивает и базу, и рассылку.
        byte[] tooBig = new byte[MessageService.MAX_CIPHERTEXT + 1];

        assertThatThrownBy(() -> messages.save(room(3600, 3600), "device-1", tooBig, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsCiphertextExactlyAtLimit() {
        byte[] atLimit = new byte[MessageService.MAX_CIPHERTEXT];

        assertThat(messages.save(room(3600, 3600), "device-1", atLimit, null).id()).isPositive();
    }

    @Test
    void livesAsLongAsTheRoomSays() {
        // Срок берётся из настроек комнаты, а не из константы: раз комната его хранит,
        // игнорировать его — значит держать в схеме поле-обманку.
        messages.save(room(3600, 3600), "device-1", "долгое".getBytes(), null);

        clock.advance(Duration.ofSeconds(120));

        assertThat(messages.since(ROOM, 0)).hasSize(1);
    }

    @Test
    void restartDoesNotResurrectExpiredMessages() {
        // Хранится абсолютный дедлайн, а не остаток TTL: простой сервера в жизни
        // сообщения не участвует. Новый MessageService поверх той же базы — модель рестарта.
        messages.save(room(60, 60), "device-1", "старое".getBytes(), null);
        clock.advance(Duration.ofSeconds(61));

        MessageService afterRestart = new MessageService(jdbc, clock);

        assertThat(afterRestart.since(ROOM, 0)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
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
            throw new UnsupportedOperationException();
        }
    }
}
