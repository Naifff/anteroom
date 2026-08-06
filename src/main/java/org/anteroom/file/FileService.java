package org.anteroom.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.anteroom.room.Room;
import org.anteroom.room.RoomService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Вложения: разрешение на загрузку, приём тела, учёт места и уборка.
 *
 * <p>Сервер видит только размер шифротекста и время. Имя файла, MIME-тип и ключ, которым
 * он зашифрован, едут внутри сообщения комнаты — в схеме их нет и быть не должно.
 *
 * <p>Загрузка идёт отдельным HTTP-запросом, а не по сокету: двадцать мегабайт по каналу
 * сообщений забили бы ленту всем участникам. Пропуском служит короткоживущий токен,
 * который выдаётся по сокету — там уже проверено членство в комнате.
 */
@Service
public class FileService {

    /**
     * Потолок одного вложения, ~21 МБ. Порог задан в байтах шифротекста, а не открытого
     * текста: иначе файл ровно по границе в 20 МБ получал бы ложный отказ на служебных
     * байтах secretstream.
     *
     * <p>Не поднимать, не перечитав CLAUDE.md: ровно на этой границе не нужны Service
     * Worker, потоковая расшифровка, возобновляемая загрузка и Range-запросы. Поднятие
     * возвращает все четыре пункта сразу.
     */
    public static final long MAX_CIPHERTEXT = 21L * 1024 * 1024;

    /**
     * Квоты — страховка, основной механизм уборки всё равно TTL. Числа те же, что в README:
     * при 52 участниках и файлах по 20 МБ пять суток накопления дают заметный объём, но
     * упереться в квоту раньше, чем сработает срок, комната не должна.
     */
    public static final long ROOM_QUOTA = 2L * 1024 * 1024 * 1024;
    public static final long DISK_QUOTA = 20L * 1024 * 1024 * 1024;

    public static final Duration TOKEN_TTL = Duration.ofSeconds(60);

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final JdbcTemplate jdbc;
    private final BlobStore blobs;
    private final Clock clock;
    private final long maxBytes;
    private final long roomQuota;
    private final long diskQuota;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /**
     * Блобы выданных, но ещё не завершённых загрузок. Без этого набора скан осиротевших
     * сносит файл, который прямо сейчас пишется: строки в базе у него ещё нет.
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public FileService(JdbcTemplate jdbc, BlobStore blobs, Clock clock,
                       @Value("${app.file.max-bytes:" + MAX_CIPHERTEXT + "}") long maxBytes,
                       @Value("${app.file.room-quota-bytes:" + ROOM_QUOTA + "}") long roomQuota,
                       @Value("${app.file.disk-quota-bytes:" + DISK_QUOTA + "}") long diskQuota) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.clock = clock;
        this.maxBytes = maxBytes;
        this.roomQuota = roomQuota;
        this.diskQuota = diskQuota;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Разрешение на одну загрузку.
     *
     * <p>Место резервируется здесь же: пока тело не приехало, объявленный размер считается
     * занятым. Иначе пачка одновременных запросов проходит квоту хором — на момент выдачи
     * в базе ещё пусто у всех.
     *
     * @param declaredSize размер шифротекста, который клиент собирается прислать
     */
    public Upload issue(Room room, String device, long declaredSize, Long requestedTtlSeconds) {
        if (declaredSize <= 0 || declaredSize > maxBytes) {
            throw new FileTooLargeException("вложение больше " + maxBytes + " байт: " + declaredSize);
        }

        long now = clock.millis();
        forgetExpired(now);

        if (reserved(room.id(), now) + declaredSize > roomQuota) {
            throw new IllegalArgumentException("в комнате кончилось место под файлы");
        }
        if (reserved(null, now) + declaredSize > diskQuota) {
            throw new IllegalArgumentException("на сервере кончилось место под файлы");
        }

        // Срок тот же, что у сообщений, и зажимается так же: он приходит от клиента.
        long wanted = requestedTtlSeconds == null ? room.defaultTtl() : requestedTtlSeconds;
        long ttlSeconds = Math.clamp(wanted, RoomService.TTL_FLOOR, room.maxTtl());

        String id = randomToken(16);
        String token = randomToken(32);
        long deadline = now + TOKEN_TTL.toMillis();

        inFlight.add(id);
        pending.put(token, new Pending(id, room.id(), device, declaredSize, ttlSeconds, deadline));
        return new Upload(id, token, deadline);
    }

    /**
     * Принимает тело загрузки.
     *
     * <p>Лимит проверяется дважды: по {@code Content-Length} до чтения и по фактически
     * прочитанным байтам во время. Первую проверку легко обойти — заголовок пишет клиент,
     * а при chunked-кодировании его нет вообще, — поэтому одна она ничего не стоит.
     *
     * @param contentLength что обещал заголовок; отрицательное — заголовка не было
     */
    public StoredFile accept(String token, InputStream body, long contentLength) {
        long now = clock.millis();
        forgetExpired(now);

        Pending reservation = token == null ? null : pending.remove(token);
        if (reservation == null) {
            // Один и тот же отказ на «не тот», «протух» и «уже потрачен»: различать их
            // снаружи значит подсказывать перебирающему, насколько он близко.
            throw new UploadRefusedException("пропуск не принят");
        }

        try {
            if (contentLength > reservation.declaredSize()) {
                throw new FileTooLargeException(
                        "заголовок обещает больше объявленного: " + contentLength);
            }

            long size;
            try {
                size = blobs.write(reservation.id(), body, reservation.declaredSize());
            } catch (IOException e) {
                throw new UncheckedIOException("не удалось записать блоб", e);
            }

            // Срок считается от момента, когда тело дочитано, а не когда выдан токен:
            // иначе он утекал бы, пока файл ещё едет по каналу.
            long expiresAt = clock.millis() + reservation.ttlSeconds() * 1000;
            jdbc.update("""
                    INSERT INTO file (id, room_id, uploader, size_bytes, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, reservation.id(), reservation.roomId(), reservation.device(),
                    size, clock.millis(), expiresAt);

            return new StoredFile(reservation.id(), reservation.roomId(), size, expiresAt);
        } catch (RuntimeException e) {
            // Строки нет — значит и блоба быть не должно, иначе на диске остаётся мусор,
            // о котором знает только скан осиротевших.
            blobs.delete(reservation.id());
            throw e;
        } finally {
            inFlight.remove(reservation.id());
        }
    }

    /**
     * Метаданные живого вложения либо {@code null}.
     *
     * <p>Фильтр по {@code expires_at} обязателен: между тиками sweeper'а протухшие строки
     * ещё лежат в базе, и отдавать по ним блоб нельзя.
     */
    public StoredFile find(String id) {
        return jdbc.query("""
                SELECT id, room_id, size_bytes, expires_at
                  FROM file
                 WHERE id = ? AND expires_at > ?
                """,
                rs -> rs.next()
                        ? new StoredFile(rs.getString("id"), rs.getString("room_id"),
                                rs.getLong("size_bytes"), rs.getLong("expires_at"))
                        : null,
                id, clock.millis());
    }

    /**
     * Уборка протухшего: <b>сначала блоб, потом строка</b>.
     *
     * <p>Обратный порядок оставляет на диске вечный мусор — строки уже нет, и никто
     * больше не знает, что этот файл кому-то принадлежал.
     */
    public int sweepExpired() {
        long now = clock.millis();
        List<String> dead = jdbc.queryForList(
                "SELECT id FROM file WHERE expires_at <= ?", String.class, now);
        for (String id : dead) {
            blobs.delete(id);
        }
        return jdbc.update("DELETE FROM file WHERE expires_at <= ?", now);
    }

    /**
     * Блобы, которым не соответствует ни одна строка.
     *
     * <p>Появляются от падения между записью тела и вставкой строки, а ещё от удаления
     * комнаты: каскад в схеме сносит строки, но про файловую систему ничего не знает.
     */
    public int removeOrphans() {
        int removed = 0;
        for (String name : blobs.names()) {
            String id = name.endsWith(BlobStore.PART_SUFFIX)
                    ? name.substring(0, name.length() - BlobStore.PART_SUFFIX.length())
                    : name;
            if (inFlight.contains(id)) {
                continue;
            }
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM file WHERE id = ?", Integer.class, id);
            if (rows != null && rows > 0 && !name.endsWith(BlobStore.PART_SUFFIX)) {
                continue;
            }
            blobs.delete(name);
            removed++;
        }
        return removed;
    }

    /** Занято в комнате либо, если {@code roomId} пуст, на всём сервере. */
    private long reserved(String roomId, long now) {
        Long stored = roomId == null
                ? jdbc.queryForObject(
                        "SELECT coalesce(sum(size_bytes), 0) FROM file WHERE expires_at > ?",
                        Long.class, now)
                : jdbc.queryForObject(
                        "SELECT coalesce(sum(size_bytes), 0) FROM file WHERE room_id = ? AND expires_at > ?",
                        Long.class, roomId, now);

        long promised = pending.values().stream()
                .filter(entry -> roomId == null || roomId.equals(entry.roomId()))
                .mapToLong(Pending::declaredSize)
                .sum();

        return (stored == null ? 0 : stored) + promised;
    }

    private void forgetExpired(long now) {
        pending.values().removeIf(entry -> {
            if (now <= entry.expiresAt()) {
                return false;
            }
            inFlight.remove(entry.id());
            return true;
        });
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return ENCODER.encodeToString(raw);
    }

    private record Pending(String id, String roomId, String device, long declaredSize,
                           long ttlSeconds, long expiresAt) {
    }
}
