package org.anteroom.invite;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.anteroom.device.DeviceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.data-dir=build/test-data-owner")
class OwnerBootstrapTest {

    private static final Path DATA_DIR = Path.of("build/test-data-owner");

    @Autowired
    private OwnerBootstrap bootstrap;

    @Autowired
    private InviteService invites;

    @Autowired
    private DeviceService devices;

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

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM invite");
        jdbc.update("DELETE FROM instance_owner");
        jdbc.update("DELETE FROM member_key");
        jdbc.update("DELETE FROM member");
        jdbc.update("DELETE FROM room");
        jdbc.update("DELETE FROM device");
    }

    @Test
    void issuesOwnerInviteOnEmptyInstance() {
        String token = bootstrap.ensureOwnerInvite();

        assertThat(token).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invite WHERE room_id IS NULL AND role = 'owner'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void doesNotIssueSecondInviteWhileFirstIsAlive() {
        // Печатается один раз. Иначе каждый рестарт выкидывает в журнал новую ссылку
        // на владение сервером.
        bootstrap.ensureOwnerInvite();

        assertThat(bootstrap.ensureOwnerInvite()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invite", Integer.class)).isEqualTo(1);
    }

    @Test
    void staysQuietOnceOwnerExists() {
        // «Owner-инвайт печатается только если в базе нет ни одного владельца»:
        // никакого повторного онбординга после рестарта.
        String token = bootstrap.ensureOwnerInvite();
        devices.rememberSigningKey("first-owner");
        invites.redeem(token, "first-owner");

        assertThat(bootstrap.ensureOwnerInvite()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invite", Integer.class)).isZero();
    }

    @Test
    void grantsInstanceOwnershipOnRedemption() {
        String token = bootstrap.ensureOwnerInvite();
        devices.rememberSigningKey("newcomer");

        Redemption result = invites.redeem(token, "newcomer");

        assertThat(result.accepted()).isTrue();
        assertThat(result.roomId()).isNull();
        assertThat(result.role()).isEqualTo("owner");
        assertThat(devices.admitted("newcomer")).isTrue();
    }

    @Test
    void keepsStrangerOutside() {
        devices.rememberSigningKey("stranger");

        // Подписавшееся устройство ещё не участник: подпись говорит «это тот же ключ»,
        // а не «его сюда звали».
        assertThat(devices.admitted("stranger")).isFalse();
    }

    @Test
    void admitsWhoeverRedeemedRoomInvite() {
        // Участие хотя бы в одной комнате — тоже пропуск: комнату может завести любой
        // участник, а не только владелец сервера.
        devices.rememberSigningKey("host");
        String roomId = rooms.create("host", 3600, 86400, true);
        devices.rememberSigningKey("guest");
        invites.create(roomId, "host", InviteHash.of("гостевой"), "member", 3600, 1, new byte[] { 1 });

        invites.redeem("гостевой", "guest");

        assertThat(devices.admitted("guest")).isTrue();
    }

    @Autowired
    private org.anteroom.room.RoomService rooms;
}
