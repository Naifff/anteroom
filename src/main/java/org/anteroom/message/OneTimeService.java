package org.anteroom.message;

import java.time.Clock;
import java.util.List;

import org.anteroom.invite.InviteHash;
import org.anteroom.room.Room;
import org.anteroom.room.RoomService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Одноразовые ссылки.
 *
 * <p>Устроены как приглашения: токен делает клиент, сюда приезжает только SHA-256, а
 * шифротекст лежит под ключом, который живёт во фрагменте ссылки и на сервер не попадает.
 *
 * <p>Открытие уничтожает содержимое немедленно, а не помечает прочитанным: пока строка
 * жива, сохранённая копия базы её отдаёт.
 */
@Service
public class OneTimeService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OneTimeService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void create(Room room, String tokenHash, byte[] ciphertext, Long requestedTtlSeconds) {
        if (ciphertext.length > MessageService.MAX_CIPHERTEXT) {
            throw new IllegalArgumentException(
                    "записка больше " + MessageService.MAX_CIPHERTEXT + " байт: " + ciphertext.length);
        }

        // Срок приходит от клиента, поэтому зажимается тем же диапазоном, что у сообщений:
        // отдельных правил для записок не заводим.
        long wanted = requestedTtlSeconds == null ? room.defaultTtl() : requestedTtlSeconds;
        long ttlSeconds = Math.clamp(wanted, RoomService.TTL_FLOOR, room.maxTtl());

        jdbc.update("""
                INSERT INTO onetime (token_hash, room_id, ciphertext, expires_at)
                VALUES (?, ?, ?, ?)
                """, tokenHash, room.id(), ciphertext, clock.millis() + ttlSeconds * 1000);
    }

    /**
     * Отдаёт содержимое и в тот же момент его уничтожает.
     *
     * <p>Удаление и чтение — один запрос с {@code RETURNING}, а не «прочитали, отдали,
     * удалили»: на два одновременных перехода второй обязан получить пустоту, а не успеть
     * прочитать ещё не удалённую строку.
     *
     * <p>Уже открытая, протухшая и никогда не существовавшая ссылка отвечают одинаково —
     * {@code null}. Различать их снаружи означало бы подтверждать, что ссылка была.
     *
     * @return шифротекст либо {@code null}
     */
    public byte[] burn(String token) {
        if (token == null) {
            return null;
        }
        List<byte[]> burned = jdbc.query("""
                DELETE FROM onetime
                 WHERE token_hash = ? AND expires_at > ?
                RETURNING ciphertext
                """,
                (rs, rowNum) -> rs.getBytes("ciphertext"), InviteHash.of(token), clock.millis());

        return burned.isEmpty() ? null : burned.get(0);
    }

    public int sweepExpired() {
        return jdbc.update("DELETE FROM onetime WHERE expires_at <= ?", clock.millis());
    }
}
