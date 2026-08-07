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

import org.anteroom.room.Room;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.data-dir=build/test-data-direct")
class DirectServiceTest {

    private static final Path DATA_DIR = Path.of("build/test-data-direct");
    private static final String ROOM = "room-a";
    private static final String OTHER_ROOM = "room-b";

    @Autowired
    private JdbcTemplate jdbc;

    private MutableClock clock;
    private DirectService direct;

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
        jdbc.update("DELETE FROM direct");
        jdbc.update("DELETE FROM room");
        jdbc.update("INSERT INTO room (id, created_at) VALUES (?, ?)", ROOM, 0);
        jdbc.update("INSERT INTO room (id, created_at) VALUES (?, ?)", OTHER_ROOM, 0);

        clock = new MutableClock(Instant.parse("2026-08-06T12:00:00Z"));
        direct = new DirectService(jdbc, clock);
    }

    @Test
    void keepsCiphertextAndEnvelopesByteForByte() {
        byte[] envelopes = envelopes();
        StoredDirect saved = direct.save(room(), "шифротекст".getBytes(), envelopes, null);

        assertThat(direct.since(ROOM, 0))
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.id()).isEqualTo(saved.id());
                    assertThat(stored.ciphertext()).isEqualTo("шифротекст".getBytes());
                    assertThat(stored.envelopes()).isEqualTo(envelopes);
                });
    }

    @Test
    void refusesEnvelopesShorterThanTheWholeDeck() {
        // Пропуск хотя бы одного слота сразу говорит, что получателя среди этих мест нет.
        byte[] short51 = new byte[51 * DirectService.SLOT];

        assertThatThrownBy(() -> direct.save(room(), "шифротекст".getBytes(), short51, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("52");
    }

    @Test
    void refusesEnvelopesLongerThanTheWholeDeck() {
        byte[] long53 = new byte[53 * DirectService.SLOT];

        assertThatThrownBy(() -> direct.save(room(), "шифротекст".getBytes(), long53, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesEnvelopesOfRaggedLength() {
        // Слоты одной длины — не косметика: длиннее прочих означает «вот настоящий».
        byte[] ragged = new byte[DirectService.ENVELOPES + 1];

        assertThatThrownBy(() -> direct.save(room(), "шифротекст".getBytes(), ragged, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storesNothingThatNamesEitherSide() {
        direct.save(room(), "шифротекст".getBytes(), envelopes(), null);

        // Схема — часть обещания: пока в ней нет этих полей, их некуда случайно записать.
        assertThat(jdbc.queryForList("SELECT * FROM direct").get(0).keySet())
                .containsExactlyInAnyOrder("id", "room_id", "ciphertext", "envelopes",
                        "created_at", "expires_at");
    }

    @Test
    void refusesWhenRoomForbidsDirectMessages() {
        Room quiet = new Room(ROOM, 1, 3600, 3600, 1, false, 0);

        assertThatThrownBy(() -> direct.save(quiet, "шифротекст".getBytes(), envelopes(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("личные");
    }

    @Test
    void refusesCiphertextOverLimit() {
        byte[] tooBig = new byte[MessageService.MAX_CIPHERTEXT + 1];

        assertThatThrownBy(() -> direct.save(room(), tooBig, envelopes(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clampsTtlToRoomCeiling() {
        // Отдельного потолка у личных сообщений нет: те же правила, тот же зажим.
        direct.save(new Room(ROOM, 1, 3600, 3600, 1, true, 0), "шифротекст".getBytes(),
                envelopes(), 999_999L);

        clock.advance(Duration.ofSeconds(3601));

        assertThat(direct.since(ROOM, 0)).isEmpty();
    }

    @Test
    void hidesExpiredBeforeSweeperRuns() {
        direct.save(new Room(ROOM, 1, 60, 60, 1, true, 0), "шифротекст".getBytes(), envelopes(), 60L);

        clock.advance(Duration.ofSeconds(61));

        assertThat(direct.since(ROOM, 0)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM direct", Integer.class))
                .as("строка ещё в базе — sweeper не отрабатывал")
                .isEqualTo(1);
    }

    @Test
    void returnsOnlyWhatIsNewerThanLastSeen() {
        long first = direct.save(room(), "первое".getBytes(), envelopes(), null).id();
        direct.save(room(), "второе".getBytes(), envelopes(), null);

        assertThat(direct.since(ROOM, first))
                .extracting(stored -> new String(stored.ciphertext()))
                .containsExactly("второе");
    }

    @Test
    void doesNotLeakBetweenRooms() {
        direct.save(new Room(OTHER_ROOM, 1, 3600, 3600, 1, true, 0), "чужое".getBytes(),
                envelopes(), null);

        assertThat(direct.since(ROOM, 0)).isEmpty();
    }

    @Test
    void sweepDeletesExpiredAndKeepsLive() {
        direct.save(new Room(ROOM, 1, 60, 60, 1, true, 0), "старое".getBytes(), envelopes(), 60L);
        clock.advance(Duration.ofSeconds(61));
        direct.save(room(), "свежее".getBytes(), envelopes(), null);

        assertThat(direct.sweepExpired()).isEqualTo(1);
        assertThat(direct.since(ROOM, 0))
                .extracting(stored -> new String(stored.ciphertext()))
                .containsExactly("свежее");
    }

    private static Room room() {
        return new Room(ROOM, 1, 3600, 3600, 1, true, 0);
    }

    private static byte[] envelopes() {
        return new byte[DirectService.ENVELOPES];
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
            return this;
        }
    }
}
