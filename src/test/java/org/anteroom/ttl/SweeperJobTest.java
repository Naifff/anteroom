package org.anteroom.ttl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.data-dir=build/test-data-sweeper")
class SweeperJobTest {

    private static final Path DATA_DIR = Path.of("build/test-data-sweeper");
    private static final long PAST = 1_000;
    private static final long FUTURE = 4_000_000_000_000L;

    @Autowired
    private SweeperJob sweeper;

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
    void seed() {
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM onetime");
        jdbc.update("DELETE FROM invite");
        jdbc.update("DELETE FROM member_key");
        jdbc.update("DELETE FROM member");
        jdbc.update("DELETE FROM room");
        jdbc.update("DELETE FROM device");

        jdbc.update("INSERT INTO room (id, created_at) VALUES ('room-a', 0)");
        jdbc.update("INSERT INTO device (pubkey_sign, pubkey_box, created_at) VALUES ('dev', '', 0)");

        for (long deadline : new long[] { PAST, FUTURE }) {
            jdbc.update("""
                    INSERT INTO message (room_id, sender, epoch, ciphertext, created_at, expires_at)
                    VALUES ('room-a', 'dev', 1, x'01', 0, ?)
                    """, deadline);
            jdbc.update("""
                    INSERT INTO onetime (token_hash, room_id, ciphertext, expires_at)
                    VALUES (?, 'room-a', x'01', ?)
                    """, "onetime-" + deadline, deadline);
            jdbc.update("""
                    INSERT INTO invite (token_hash, room_id, role, created_by, wrapped_key, expires_at, uses_left)
                    VALUES (?, 'room-a', 'member', 'dev', x'01', ?, 1)
                    """, "invite-" + deadline, deadline);
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void sweepsExpiredMessages() {
        sweeper.sweep();

        assertThat(count("message")).isEqualTo(1);
    }

    @Test
    void sweepsExpiredOneTimeLinks() {
        // Одноразовые ссылки протухают наравне с сообщениями: непрочитанная не должна
        // лежать вечно.
        sweeper.sweep();

        assertThat(count("onetime")).isEqualTo(1);
    }

    @Test
    void sweepsExpiredInvites() {
        sweeper.sweep();

        assertThat(count("invite")).isEqualTo(1);
    }

    @Test
    void sweepsSpentInvites() {
        // Исчерпанный инвайт держит на сервере wrapped_key — ключ комнаты под эфемерным
        // ключом ссылки. Пока строка жива, сохранённая ссылка остаётся заряженной.
        jdbc.update("""
                INSERT INTO invite (token_hash, room_id, role, created_by, wrapped_key, expires_at, uses_left)
                VALUES ('spent', 'room-a', 'member', 'dev', x'01', ?, 0)
                """, FUTURE);

        sweeper.sweep();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invite WHERE token_hash = 'spent'", Integer.class)).isZero();
    }

    @Test
    void keepsWhatIsStillAlive() {
        sweeper.sweep();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM message WHERE expires_at = ?", Integer.class, FUTURE)).isEqualTo(1);
    }

    @Test
    void sweepsExpiredDirectMessages() {
        for (long deadline : new long[] { PAST, FUTURE }) {
            jdbc.update("""
                    INSERT INTO direct (room_id, ciphertext, envelopes, created_at, expires_at)
                    VALUES ('room-a', x'01', x'02', 0, ?)
                    """, deadline);
        }

        sweeper.sweep();

        assertThat(count("direct")).isEqualTo(1);
    }

    @Test
    void sweepsExpiredFileWithItsBlob() throws IOException {
        // Порядок обязателен: сначала блоб, потом строка. Обратный оставляет на диске
        // вечный мусор, о котором больше никто не знает.
        Path blob = blob("протухший");
        Files.write(blob, new byte[8]);
        jdbc.update("""
                INSERT INTO file (id, room_id, uploader, size_bytes, created_at, expires_at)
                VALUES ('протухший', 'room-a', 'dev', 8, 0, ?)
                """, PAST);

        sweeper.sweep();

        assertThat(count("file")).isZero();
        assertThat(Files.exists(blob)).as("блоб ушёл вместе со строкой").isFalse();
    }

    @Test
    void keepsBlobOfLiveFile() throws IOException {
        Path blob = blob("живой");
        Files.write(blob, new byte[8]);
        jdbc.update("""
                INSERT INTO file (id, room_id, uploader, size_bytes, created_at, expires_at)
                VALUES ('живой', 'room-a', 'dev', 8, 0, ?)
                """, FUTURE);

        sweeper.sweep();

        assertThat(count("file")).isEqualTo(1);
        assertThat(Files.exists(blob)).isTrue();
    }

    @Test
    void scansForOrphanBlobsAtStartup() throws IOException {
        // Каскад при удалении комнаты сносит строки, но про файловую систему не знает,
        // и падение между записью тела и вставкой строки оставляет то же самое.
        Path blob = blob("ничей");
        Files.write(blob, new byte[8]);

        sweeper.sweepOrphanBlobs();

        assertThat(Files.exists(blob)).isFalse();
    }

    private Path blob(String id) throws IOException {
        Path dir = DATA_DIR.resolve("blobs");
        Files.createDirectories(dir);
        return dir.resolve(id);
    }

    @Test
    void returnsSpaceWithoutFullVacuum() {
        // Полный VACUUM блокирует базу целиком и требует вдвое больше места на диске.
        // Место должен возвращать incremental_vacuum порциями — и не падать на пустой базе.
        sweeper.sweep();
        sweeper.sweep();

        assertThat(jdbc.queryForObject("PRAGMA auto_vacuum", Integer.class))
                .as("режим уборки должен остаться инкрементальным")
                .isEqualTo(2);
    }
}
