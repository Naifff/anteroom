package org.anteroom.message;

import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

@Service
public class MessageService {

    /**
     * Срок жизни сообщения в скелете. Постоянная и короткая нарочно: фаза 1 проверяет,
     * что протухшее исчезает, а не что сроками можно управлять. Выбор срока отправителем
     * и зажим на сервере — фаза 7.
     */
    static final Duration SKELETON_TTL = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MessageService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public StoredMessage save(String roomId, String sender, int epoch, byte[] ciphertext) {
        long now = clock.millis();
        // Хранится абсолютный дедлайн, а не остаток срока: рестарт и простой сервера
        // в жизни сообщения не участвуют.
        long expiresAt = now + SKELETON_TTL.toMillis();

        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO message (room_id, sender, epoch, ciphertext, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[] { "id" });
            statement.setString(1, roomId);
            statement.setString(2, sender);
            statement.setInt(3, epoch);
            statement.setBytes(4, ciphertext);
            statement.setLong(5, now);
            statement.setLong(6, expiresAt);
            return statement;
        }, key);

        // Возвращается запись целиком, а не id: обработчику нужен дедлайн, и подставлять
        // вместо него ноль — заготовка для бага в тот день, когда клиент начнёт его читать.
        return new StoredMessage(key.getKey().longValue(), sender, epoch, ciphertext, expiresAt);
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
}
