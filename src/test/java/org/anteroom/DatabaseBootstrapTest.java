package org.anteroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Старт с нуля: каталог создан, миграции прогнаны, режимы SQLite те, на которые
 * рассчитана вся остальная схема. Проверять их «глазами в логе» нельзя — оба режима
 * при неверной настройке молчат и просто не работают.
 */
@SpringBootTest(properties = "app.data-dir=build/test-data")
class DatabaseBootstrapTest {

    private static final Path DATA_DIR = Path.of("build/test-data");

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startFromEmptyDirectory() throws IOException {
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

    @Test
    void createsDataDirectoryWithBlobs() {
        assertThat(DATA_DIR.resolve("messenger.db")).exists();
        assertThat(DATA_DIR.resolve("blobs")).isDirectory();
    }

    @Test
    void appliesFirstMigration() {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", String.class);

        assertThat(tables).contains("room", "device", "member", "member_key", "invite", "message", "onetime");
        assertThat(jdbc.queryForObject(
                "SELECT MAX(version) FROM flyway_schema_history", String.class)).isEqualTo("1");
    }

    @Test
    void enforcesForeignKeys() {
        // Выключены по умолчанию. Без них ON DELETE CASCADE во всей схеме — просто текст,
        // и удаление комнаты оставит её сообщения и инвайты жить дальше.
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
    }

    @Test
    void reclaimsSpaceIncrementally() {
        // 2 = INCREMENTAL. При 0 место после уборки протухших сообщений не возвращается,
        // а починить это потом можно только полным VACUUM.
        assertThat(jdbc.queryForObject("PRAGMA auto_vacuum", Integer.class)).isEqualTo(2);
    }

    @Test
    void usesWriteAheadLog() {
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualTo("wal");
    }
}
