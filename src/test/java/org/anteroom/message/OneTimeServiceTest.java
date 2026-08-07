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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.anteroom.invite.InviteHash;
import org.anteroom.room.Room;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.data-dir=build/test-data-onetime")
class OneTimeServiceTest {

    private static final Path DATA_DIR = Path.of("build/test-data-onetime");
    private static final String ROOM = "room-a";

    @Autowired
    private JdbcTemplate jdbc;

    private MutableClock clock;
    private OneTimeService links;

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
        jdbc.update("DELETE FROM onetime");
        jdbc.update("DELETE FROM room");
        jdbc.update("INSERT INTO room (id, created_at) VALUES (?, ?)", ROOM, 0);

        clock = new MutableClock(Instant.parse("2026-08-06T12:00:00Z"));
        links = new OneTimeService(jdbc, clock);
    }

    @Test
    void handsCiphertextToTheFirstAndNothingToTheSecond() {
        byte[] secret = "шифротекст записки".getBytes();
        links.create(room(3600), InviteHash.of("токен"), secret, null);

        assertThat(links.burn("токен")).isEqualTo(secret);
        assertThat(links.burn("токен")).as("второй раз ссылка пуста").isNull();
    }

    @Test
    void answersBurnedAndUnknownTheSameWay() {
        // Различать «уже открыли» и «такого не было» наружу нельзя: это подсказка о том,
        // что ссылка вообще существовала.
        links.create(room(3600), InviteHash.of("токен"), "записка".getBytes(), null);
        links.burn("токен");

        assertThat(links.burn("токен")).isEqualTo(links.burn("никогда-не-выдавался"));
    }

    @Test
    void burningRemovesTheRow() {
        links.create(room(3600), InviteHash.of("токен"), "записка".getBytes(), null);

        links.burn("токен");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM onetime", Integer.class))
                .as("шифротекст уходит из базы в момент открытия, а не по TTL")
                .isZero();
    }

    @Test
    void hidesExpiredLinkBeforeSweeperRuns() {
        links.create(room(60), InviteHash.of("токен"), "записка".getBytes(), 60L);

        clock.advance(Duration.ofSeconds(61));

        assertThat(links.burn("токен")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onetime", Integer.class))
                .as("строка ещё в базе — sweeper не отрабатывал")
                .isEqualTo(1);
    }

    @Test
    void clampsTtlToRoomCeiling() {
        links.create(room(3600), InviteHash.of("токен"), "записка".getBytes(), 999_999L);

        clock.advance(Duration.ofSeconds(3601));

        assertThat(links.burn("токен")).isNull();
    }

    @Test
    void fallsBackToRoomDefaultWhenNoTtlAsked() {
        links.create(room(3600), InviteHash.of("токен"), "записка".getBytes(), null);

        clock.advance(Duration.ofSeconds(3599));
        assertThat(links.burn("токен")).isNotNull();
    }

    @Test
    void refusesCiphertextOverLimit() {
        byte[] tooBig = new byte[MessageService.MAX_CIPHERTEXT + 1];

        assertThatThrownBy(() -> links.create(room(3600), InviteHash.of("токен"), tooBig, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sweepDeletesExpiredAndKeepsLive() {
        links.create(room(60), InviteHash.of("старая"), "старая".getBytes(), 60L);
        clock.advance(Duration.ofSeconds(61));
        links.create(room(3600), InviteHash.of("свежая"), "свежая".getBytes(), 3600L);

        assertThat(links.sweepExpired()).isEqualTo(1);
        assertThat(links.burn("свежая")).isNotNull();
    }

    @Test
    void onlyOneOfEightSimultaneousOpensGetsTheSecret() throws Exception {
        // Гонка на одноразовой ссылке — это ровно та ситуация, ради которой она одноразовая.
        // Списание идёт одним запросом с условием, а не «прочитали, проверили, удалили».
        byte[] secret = "записка".getBytes();
        links.create(room(3600), InviteHash.of("токен"), secret, null);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<byte[]>> attempts = java.util.Collections.nCopies(8, () -> links.burn("токен"));
            List<Future<byte[]>> results = pool.invokeAll(attempts);

            long winners = 0;
            for (Future<byte[]> result : results) {
                if (result.get() != null) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    private static Room room(long ttl) {
        return new Room(ROOM, 1, ttl, ttl, 1, true, 0);
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
