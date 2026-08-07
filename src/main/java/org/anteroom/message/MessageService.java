package org.anteroom.message;

import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.List;

import org.anteroom.room.Room;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

@Service
public class MessageService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MessageService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Потолок одной реплики. Без него один кадр забивает и базу, и рассылку. */
    public static final int MAX_CIPHERTEXT = 64 * 1024;

    /** Пол срока жизни. Ниже минуты сообщение не живёт даже по просьбе отправителя. */
    public static final long TTL_FLOOR = 60;

    /**
     * Кладёт реплику и назначает ей дедлайн.
     *
     * <p><b>Срок приходит от клиента, поэтому сервер обязан его зажимать</b> в
     * {@code [TTL_FLOOR, room.maxTtl]}. Принять присланное число как есть — значит отдать
     * пятидневный потолок на усмотрение того, кто шлёт кадры.
     *
     * <p>Молчание отправителя — это срок комнаты по умолчанию, а не «навсегда».
     *
     * @param requestedTtlSeconds что попросил отправитель; {@code null} — не просил ничего
     */
    public StoredMessage save(Room room, String sender, byte[] ciphertext, Long requestedTtlSeconds) {
        if (ciphertext.length > MAX_CIPHERTEXT) {
            throw new IllegalArgumentException(
                    "сообщение больше " + MAX_CIPHERTEXT + " байт: " + ciphertext.length);
        }

        long wanted = requestedTtlSeconds == null ? room.defaultTtl() : requestedTtlSeconds;
        long ttlSeconds = Math.clamp(wanted, TTL_FLOOR, room.maxTtl());

        long now = clock.millis();
        // Хранится абсолютный дедлайн, а не остаток срока: рестарт и простой сервера
        // в жизни сообщения не участвуют.
        long expiresAt = now + ttlSeconds * 1000;

        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO message (room_id, sender, epoch, ciphertext, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[] { "id" });
            statement.setString(1, room.id());
            statement.setString(2, sender);
            statement.setInt(3, room.keyEpoch());
            statement.setBytes(4, ciphertext);
            statement.setLong(5, now);
            statement.setLong(6, expiresAt);
            return statement;
        }, key);

        // Возвращается запись целиком, а не id: обработчику нужен дедлайн, и подставлять
        // вместо него ноль — заготовка для бага в тот день, когда клиент начнёт его читать.
        return new StoredMessage(key.getKey().longValue(), sender, room.keyEpoch(), ciphertext, expiresAt);
    }

    /**
     * Догрузка после реконнекта. Фильтр по {@code expires_at} обязателен: между тиками
     * sweeper'а протухшие строки ещё лежат в базе, и отдавать их наружу нельзя.
     */
    public List<StoredMessage> since(String roomId, long lastSeenId) {
        return jdbc.query("""
                SELECT id, sender, epoch, ciphertext, expires_at
                  FROM message
                 WHERE room_id = ? AND id > ? AND expires_at > ?
                 ORDER BY id
                """,
                (rs, rowNum) -> new StoredMessage(
                        rs.getLong("id"),
                        rs.getString("sender"),
                        rs.getInt("epoch"),
                        rs.getBytes("ciphertext"),
                        rs.getLong("expires_at")),
                roomId, lastSeenId, clock.millis());
    }

    public int sweepExpired() {
        return jdbc.update("DELETE FROM message WHERE expires_at <= ?", clock.millis());
    }

    public int wipeRoom(String roomId) {
        return jdbc.update("DELETE FROM message WHERE room_id = ?", roomId);
    }
}
