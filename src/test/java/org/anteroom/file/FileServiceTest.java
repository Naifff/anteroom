package org.anteroom.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

@SpringBootTest(properties = "app.data-dir=build/test-data-file")
class FileServiceTest {

    private static final Path DATA_DIR = Path.of("build/test-data-file");
    private static final String ROOM = "room-a";
    private static final String OTHER_ROOM = "room-b";
    private static final String DEVICE = "device-1";

    /** Потолок в тестах маленький: смысл проверок от числа не зависит, а мегабайты тормозят. */
    private static final long MAX = 1024;
    private static final long ROOM_QUOTA = 4096;
    private static final long DISK_QUOTA = 8192;

    @Autowired
    private JdbcTemplate jdbc;

    private MutableClock clock;
    private BlobStore blobs;
    private FileService files;

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
    void setUp() throws IOException {
        jdbc.update("DELETE FROM file");
        jdbc.update("DELETE FROM room");
        jdbc.update("INSERT INTO room (id, created_at) VALUES (?, ?)", ROOM, 0);
        jdbc.update("INSERT INTO room (id, created_at) VALUES (?, ?)", OTHER_ROOM, 0);

        Path blobDir = DATA_DIR.resolve("blobs");
        Files.createDirectories(blobDir);
        try (var paths = Files.list(blobDir)) {
            paths.forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        clock = new MutableClock(Instant.parse("2026-08-06T12:00:00Z"));
        blobs = new BlobStore(DATA_DIR.toString());
        files = new FileService(jdbc, blobs, clock, MAX, ROOM_QUOTA, DISK_QUOTA);
    }

    @Test
    void keepsCiphertextByteForByte() throws IOException {
        byte[] body = "шифротекст, сервер его не разбирает".getBytes();

        Upload upload = files.issue(room(), DEVICE, body.length, null);
        StoredFile stored = files.accept(upload.token(), stream(body), body.length);

        assertThat(stored.id()).isEqualTo(upload.id());
        assertThat(stored.sizeBytes()).isEqualTo(body.length);
        assertThat(Files.readAllBytes(blobs.path(upload.id()))).isEqualTo(body);
    }

    @Test
    void tokenWorksOnlyOnce() {
        byte[] body = "один раз".getBytes();
        Upload upload = files.issue(room(), DEVICE, body.length, null);
        files.accept(upload.token(), stream(body), body.length);

        assertThatThrownBy(() -> files.accept(upload.token(), stream(body), body.length))
                .isInstanceOf(UploadRefusedException.class);
    }

    @Test
    void refusesExpiredToken() {
        byte[] body = "поздно".getBytes();
        Upload upload = files.issue(room(), DEVICE, body.length, null);

        clock.advance(FileService.TOKEN_TTL.plusSeconds(1));

        assertThatThrownBy(() -> files.accept(upload.token(), stream(body), body.length))
                .isInstanceOf(UploadRefusedException.class);
    }

    @Test
    void refusesUnknownToken() {
        assertThatThrownBy(() -> files.accept("такого не выдавали", stream(new byte[1]), 1))
                .isInstanceOf(UploadRefusedException.class);
    }

    @Test
    void refusesDeclaredSizeOverHardLimit() {
        assertThatThrownBy(() -> files.issue(room(), DEVICE, MAX + 1, null))
                .isInstanceOf(FileTooLargeException.class);
    }

    @Test
    void refusesContentLengthOverWhatWasDeclared() {
        // Первая из двух проверок: до чтения тела, по заголовку.
        Upload upload = files.issue(room(), DEVICE, 10, null);

        assertThatThrownBy(() -> files.accept(upload.token(), stream(new byte[10]), 5000))
                .isInstanceOf(FileTooLargeException.class);
    }

    @Test
    void cutsBodyThatOutgrowsWhatWasDeclared() throws IOException {
        // Вторая проверка: по фактически прочитанным байтам. Content-Length подделан
        // или его нет вовсе — тело всё равно обрывается на пороге.
        Upload upload = files.issue(room(), DEVICE, 10, null);

        assertThatThrownBy(() -> files.accept(upload.token(), stream(new byte[900]), -1))
                .isInstanceOf(FileTooLargeException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM file", Integer.class))
                .as("строки нет: тело не дочитано, значит и файла нет")
                .isZero();
        assertThat(blobDirNames())
                .as("на диске не осталось ни блоба, ни куска")
                .isEmpty();
    }

    @Test
    void refusesWhenRoomQuotaIsSpent() {
        fillRoom(ROOM, ROOM_QUOTA);

        assertThatThrownBy(() -> files.issue(room(), DEVICE, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("комнат");
    }

    @Test
    void roomQuotaIsCountedPerRoom() {
        fillRoom(OTHER_ROOM, ROOM_QUOTA - 1);

        assertThat(files.issue(room(), DEVICE, 1, null).id()).isNotBlank();
    }

    @Test
    void refusesWhenDiskQuotaIsSpent() {
        // Комнатная квота у каждой своя, общая — одна на всех. Иначе десяток комнат
        // законно выбирает диск целиком.
        fillRoom(ROOM, ROOM_QUOTA);
        fillRoom(OTHER_ROOM, ROOM_QUOTA);

        jdbc.update("INSERT INTO room (id, created_at) VALUES ('room-c', 0)");
        Room third = new Room("room-c", 1, 3600, 3600, 1, 0);

        assertThatThrownBy(() -> files.issue(third, DEVICE, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("сервер");
    }

    @Test
    void countsIssuedButUnfinishedUploadsAgainstQuota() {
        // Без учёта выданных токенов пачка одновременных запросов проходит квоту хором:
        // на момент выдачи в базе ещё пусто у всех.
        for (int i = 0; i < 4; i++) {
            files.issue(room(), DEVICE, 1024, null);
        }

        assertThatThrownBy(() -> files.issue(room(), DEVICE, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forgetsReservationOfExpiredToken() {
        for (int i = 0; i < 4; i++) {
            files.issue(room(), DEVICE, 1024, null);
        }

        clock.advance(FileService.TOKEN_TTL.plusSeconds(1));

        assertThat(files.issue(room(), DEVICE, 1, null).id()).isNotBlank();
    }

    @Test
    void clampsTtlToRoomCeiling() {
        byte[] body = "долгожитель".getBytes();
        Upload upload = files.issue(roomWithTtl(3600), DEVICE, body.length, 999_999L);
        StoredFile stored = files.accept(upload.token(), stream(body), body.length);

        assertThat(stored.expiresAt()).isEqualTo(clock.millis() + 3600 * 1000);
    }

    @Test
    void countsTtlFromTheMomentBodyLanded() {
        // Срок начинает течь, когда тело дочитано, а не когда выдан токен: иначе он
        // утекал бы, пока файл ещё едет по каналу.
        byte[] body = "в пути".getBytes();
        Upload upload = files.issue(roomWithTtl(3600), DEVICE, body.length, 3600L);

        clock.advance(Duration.ofSeconds(30));
        StoredFile stored = files.accept(upload.token(), stream(body), body.length);

        assertThat(stored.expiresAt()).isEqualTo(clock.millis() + 3600 * 1000);
    }

    @Test
    void hidesExpiredFileBeforeSweeperRuns() {
        byte[] body = "минутка".getBytes();
        Upload upload = files.issue(roomWithTtl(60), DEVICE, body.length, 60L);
        files.accept(upload.token(), stream(body), body.length);

        clock.advance(Duration.ofSeconds(61));

        assertThat(files.find(upload.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file", Integer.class))
                .as("строка ещё в базе — sweeper не отрабатывал")
                .isEqualTo(1);
    }

    @Test
    void sweepRemovesBlobAndRow() {
        byte[] body = "минутка".getBytes();
        Upload upload = files.issue(roomWithTtl(60), DEVICE, body.length, 60L);
        files.accept(upload.token(), stream(body), body.length);

        clock.advance(Duration.ofSeconds(61));
        int deleted = files.sweepExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(blobDirNames()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file", Integer.class)).isZero();
    }

    @Test
    void sweepKeepsLiveFile() {
        byte[] body = "свежее".getBytes();
        Upload upload = files.issue(roomWithTtl(3600), DEVICE, body.length, 3600L);
        files.accept(upload.token(), stream(body), body.length);

        clock.advance(Duration.ofSeconds(61));

        assertThat(files.sweepExpired()).isZero();
        assertThat(files.find(upload.id())).isNotNull();
    }

    @Test
    void removesOrphanBlobLeftByCrash() throws IOException {
        // Падение между записью блоба и вставкой строки оставляет на диске мусор,
        // о котором больше никто не знает.
        Files.write(DATA_DIR.resolve("blobs").resolve("ничей"), new byte[16]);

        assertThat(files.removeOrphans()).isEqualTo(1);
        assertThat(blobDirNames()).isEmpty();
    }

    @Test
    void removesHalfWrittenPart() throws IOException {
        Files.write(DATA_DIR.resolve("blobs").resolve("обрыв" + BlobStore.PART_SUFFIX), new byte[16]);

        assertThat(files.removeOrphans()).isEqualTo(1);
        assertThat(blobDirNames()).isEmpty();
    }

    @Test
    void keepsBlobThatStillHasARow() {
        byte[] body = "с хозяином".getBytes();
        Upload upload = files.issue(roomWithTtl(3600), DEVICE, body.length, 3600L);
        files.accept(upload.token(), stream(body), body.length);

        assertThat(files.removeOrphans()).isZero();
        assertThat(blobDirNames()).containsExactly(upload.id());
    }

    @Test
    void keepsBlobOfUploadStillInFlight() throws IOException {
        // Скан идёт и после старта, а порт открывается раньше: снести блоб загрузки,
        // которая прямо сейчас идёт, нельзя. Признак «идёт» — невозвращённый токен,
        // а не возраст файла: сравнивать часы приложения с временем на диске значит
        // считать, что они всегда идут вместе.
        Upload upload = files.issue(room(), DEVICE, 16, null);
        Files.write(blobs.path(upload.id()), new byte[16]);

        assertThat(files.removeOrphans()).isZero();
        assertThat(blobDirNames()).containsExactly(upload.id());
    }

    @Test
    void refusesIdThatWouldEscapeTheBlobDirectory() {
        // id приезжает из адреса скачивания. Без проверки «../../» уводит чтение
        // за пределы каталога данных.
        assertThatThrownBy(() -> blobs.path("../messenger.db"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> blobs.path("подкаталог/файл"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restartDoesNotResurrectExpiredFiles() {
        byte[] body = "старое".getBytes();
        Upload upload = files.issue(roomWithTtl(60), DEVICE, body.length, 60L);
        files.accept(upload.token(), stream(body), body.length);
        clock.advance(Duration.ofSeconds(61));

        FileService afterRestart = new FileService(jdbc, blobs, clock, MAX, ROOM_QUOTA, DISK_QUOTA);

        assertThat(afterRestart.find(upload.id())).isNull();
    }

    private static Room room() {
        return roomWithTtl(3600);
    }

    private static Room roomWithTtl(long ttl) {
        return new Room(ROOM, 1, ttl, ttl, 1, 0);
    }

    private void fillRoom(String roomId, long bytes) {
        jdbc.update("""
                INSERT INTO file (id, room_id, uploader, size_bytes, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "заполнитель-" + roomId, roomId, DEVICE, bytes, clock.millis(),
                clock.millis() + 3_600_000);
    }

    private java.util.List<String> blobDirNames() {
        try (var paths = Files.list(DATA_DIR.resolve("blobs"))) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static InputStream stream(byte[] body) {
        return new ByteArrayInputStream(body);
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
