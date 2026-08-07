package org.anteroom.invite;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.anteroom.device.DeviceService;
import org.anteroom.room.RoomService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.data-dir=build/test-data-invite")
class InviteServiceTest {

    private static final Path DATA_DIR = Path.of("build/test-data-invite");

    @Autowired
    private InviteService invites;

    @Autowired
    private RoomService rooms;

    @Autowired
    private DeviceService devices;

    @Autowired
    private JdbcTemplate jdbc;

    private String roomId;

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
    void clear() {
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM invite");
        jdbc.update("DELETE FROM member_key");
        jdbc.update("DELETE FROM member");
        jdbc.update("DELETE FROM room");
        jdbc.update("DELETE FROM device");

        devices.rememberSigningKey("owner");
        roomId = rooms.create("owner", 3600, 86400, true);
    }

    /** Клиент делает токен сам и присылает серверу только его хэш. */
    private static String hashOf(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String issue(String token, int uses, long ttlSeconds) {
        invites.create(roomId, "owner", hashOf(token), "member", ttlSeconds, uses, new byte[] { 7, 7, 7 });
        return token;
    }

    private String device(String name) {
        devices.rememberSigningKey(name);
        return name;
    }

    @Test
    void storesOnlyHashOfToken() {
        // Сам токен на сервер не попадает вовсе: его делает клиент, сервер получает хэш.
        // В базе не должно быть ничего, чем можно открыть комнату.
        issue("секретный-токен", 1, 3600);

        List<String> stored = jdbc.queryForList("SELECT token_hash FROM invite", String.class);
        assertThat(stored).hasSize(1).doesNotContain("секретный-токен");
    }

    @Test
    void redeemsValidInvite() {
        String token = issue("токен-1", 1, 3600);

        Redemption result = invites.redeem(token, device("newcomer"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.roomId()).isEqualTo(roomId);
        assertThat(result.wrappedKey()).containsExactly(7, 7, 7);
        assertThat(rooms.role(roomId, "newcomer")).isEqualTo("member");
    }

    @Test
    void refusesSecondUseOfOneTimeInvite() {
        String token = issue("токен-2", 1, 3600);
        invites.redeem(token, device("first"));

        assertThat(invites.redeem(token, device("second")).accepted()).isFalse();
    }

    @Test
    void allowsAsManyUsesAsIssued() {
        String token = issue("токен-3", 3, 3600);

        for (int i = 0; i < 3; i++) {
            assertThat(invites.redeem(token, device("guest-" + i)).accepted()).isTrue();
        }
        assertThat(invites.redeem(token, device("guest-late")).accepted()).isFalse();
    }

    @Test
    void refusesExpiredInvite() {
        String token = issue("токен-4", 1, 60);

        clock.advance(Duration.ofSeconds(61));

        assertThat(invites.redeem(token, device("late")).accepted()).isFalse();
    }

    @Test
    void answersTheSameToInvalidExpiredAndSpentToken() {
        // Различать их наружу нельзя: по разнице ответов видно, какой инвайт существовал.
        String spent = issue("токен-5", 1, 3600);
        invites.redeem(spent, device("used-it"));
        String expired = issue("токен-6", 1, 60);
        clock.advance(Duration.ofSeconds(61));

        Redemption unknown = invites.redeem("такого-не-было", device("a"));
        Redemption exhausted = invites.redeem(spent, device("b"));
        Redemption stale = invites.redeem(expired, device("c"));

        assertThat(List.of(unknown, exhausted, stale))
                .allSatisfy(answer -> {
                    assertThat(answer.accepted()).isFalse();
                    assertThat(answer.reason()).isEqualTo(Redemption.REFUSED);
                    assertThat(answer.roomId()).isNull();
                    assertThat(answer.wrappedKey()).isNull();
                });
    }

    @Test
    void dropsWrappedKeyOnceInviteIsSpent() {
        // Иначе пересланная в мессенджер ссылка навсегда остаётся ключом от комнаты,
        // даже после того как инвайт исчерпан, — а превью-боты и архивы её видят.
        String token = issue("токен-7", 1, 3600);
        invites.redeem(token, device("newcomer"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM invite", Integer.class)).isZero();
    }

    @Test
    void keepsInviteWhileUsesRemain() {
        String token = issue("токен-8", 2, 3600);
        invites.redeem(token, device("first"));

        assertThat(jdbc.queryForObject("SELECT uses_left FROM invite", Integer.class)).isEqualTo(1);
    }

    @Test
    void remembersWhoInvited() {
        String token = issue("токен-9", 1, 3600);
        invites.redeem(token, device("newcomer"));

        assertThat(jdbc.queryForObject(
                "SELECT invited_by FROM member WHERE room_id = ? AND pubkey_sign = ?",
                String.class, roomId, "newcomer")).isEqualTo("owner");
    }

    @Test
    void letsSameDeviceRedeemTwiceWithoutSpendingSecondSeat() {
        // Повторный переход по своей же ссылке — не новый участник и не новая карта.
        String token = issue("токен-10", 2, 3600);
        Redemption first = invites.redeem(token, device("returning"));
        Redemption again = invites.redeem(token, "returning");

        assertThat(first.accepted()).isTrue();
        assertThat(again.accepted()).isTrue();
        assertThat(rooms.find(roomId).seatsTaken()).isEqualTo(2);
    }

    @Test
    void revokesInvite() {
        String token = issue("токен-11", 5, 3600);

        invites.revoke(roomId, hashOf(token));

        assertThat(invites.redeem(token, device("too-late")).accepted()).isFalse();
    }

    @Test
    void revokesEveryInviteOfMember() {
        // Каскадный отзыв по invited_by — по явной команде, не автоматом при исключении.
        String token = issue("токен-12", 1, 3600);
        invites.redeem(token, device("recruiter"));
        invites.create(roomId, "recruiter", hashOf("его-токен"), "member", 3600, 5, new byte[] { 1 });

        invites.revokeIssuedBy(roomId, "recruiter");

        assertThat(invites.redeem("его-токен", device("nobody")).accepted()).isFalse();
    }

    @Test
    void doesNotTouchInvitesOfOthersOnCascade() {
        issue("чужой-токен", 1, 3600);
        invites.revokeIssuedBy(roomId, "recruiter");

        assertThat(invites.redeem("чужой-токен", device("guest")).accepted()).isTrue();
    }

    @Test
    void spendsOneTimeInviteExactlyOnceUnderRace() throws Exception {
        // Критерий фазы: два параллельных перехода по одноразовой ссылке дают ровно
        // одного участника, а не двух.
        String token = issue("гоночный", 1, 3600);
        int racers = 8;
        IntStream.range(0, racers).forEach(i -> device("racer-" + i));

        CyclicBarrier start = new CyclicBarrier(racers);
        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            List<Callable<Boolean>> attempts = IntStream.range(0, racers)
                    .mapToObj(i -> (Callable<Boolean>) () -> {
                        start.await();
                        return invites.redeem(token, "racer-" + i).accepted();
                    })
                    .toList();

            long accepted = pool.invokeAll(attempts).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();

            assertThat(accepted).isEqualTo(1);
        }
        assertThat(rooms.find(roomId).seatsTaken()).isEqualTo(2);
    }

    @Autowired
    private MutableClock clock;

    /** Часы приложения подменены на управляемые: сроки инвайтов проверяются без sleep. */
    @org.springframework.boot.test.context.TestConfiguration
    static class Clocks {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-08-05T12:00:00Z"));
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
            throw new UnsupportedOperationException();
        }
    }
}
