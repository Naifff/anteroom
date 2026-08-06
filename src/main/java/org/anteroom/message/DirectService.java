package org.anteroom.message;

import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.List;

import org.anteroom.room.CardDealer;
import org.anteroom.room.Room;
import org.anteroom.room.RoomService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * Личные сообщения.
 *
 * <p><b>Получатель скрыт от сервера рассылкой на всю колоду.</b> Текст шифруется случайным
 * ключом один раз, а ключ кладётся в 52 конверта по числу мест: в слоте получателя настоящий
 * sealed box, в остальных случайные байты той же длины. Вывод sealed box неотличим от
 * случайного для того, у кого нет ключа, поэтому сервер не знает, кому адресовано.
 *
 * <p>Отсюда три правила, нарушение любого раскрывает получателя, и все три проверяются
 * здесь, а не подразумеваются:
 * <ul>
 *   <li>слотов всегда ровно 52, включая места выбывших — пропуск говорит «здесь не он»;
 *   <li>все слоты одной длины — длиннее прочих значит «вот настоящий»;
 *   <li>в схеме нет ни поля получателя, ни отправителя.
 * </ul>
 *
 * <p>Проверять содержимое конвертов сервер не может и не должен: для него это случайные
 * байты, и именно в этом смысл. Он отвечает только за форму.
 */
@Service
public class DirectService {

    /** Мест в колоде. Слотов всегда столько же — и выбывшие тоже занимают своё. */
    public static final int DECK = CardDealer.DECK_SIZE;

    /** Слот: sealed box над 32-байтовым ключом сообщения, то есть 32 + crypto_box_SEALBYTES. */
    public static final int SLOT = 32 + 48;

    public static final int ENVELOPES = DECK * SLOT;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DirectService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public StoredDirect save(Room room, byte[] ciphertext, byte[] envelopes, Long requestedTtlSeconds) {
        if (!room.directAllowed()) {
            throw new IllegalArgumentException("в этой комнате личные сообщения запрещены");
        }
        if (ciphertext.length > MessageService.MAX_CIPHERTEXT) {
            throw new IllegalArgumentException(
                    "сообщение больше " + MessageService.MAX_CIPHERTEXT + " байт: " + ciphertext.length);
        }
        if (envelopes.length != ENVELOPES) {
            throw new IllegalArgumentException(
                    "конвертов должно быть ровно 52 по " + SLOT + " байт: пришло " + envelopes.length);
        }

        // Срок и зажим те же, что у обычных сообщений: отдельных правил для личных не заводим.
        long wanted = requestedTtlSeconds == null ? room.defaultTtl() : requestedTtlSeconds;
        long ttlSeconds = Math.clamp(wanted, RoomService.TTL_FLOOR, room.maxTtl());

        long now = clock.millis();
        long expiresAt = now + ttlSeconds * 1000;

        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO direct (room_id, ciphertext, envelopes, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, new String[] { "id" });
            statement.setString(1, room.id());
            statement.setBytes(2, ciphertext);
            statement.setBytes(3, envelopes);
            statement.setLong(4, now);
            statement.setLong(5, expiresAt);
            return statement;
        }, key);

        return new StoredDirect(key.getKey().longValue(), ciphertext, envelopes, expiresAt);
    }

    /**
     * Догрузка после реконнекта. Отдаётся всем участникам комнаты целиком: разбираться,
     * кому оно, — работа клиента, и только клиента.
     */
    public List<StoredDirect> since(String roomId, long lastSeenId) {
        return jdbc.query("""
                SELECT id, ciphertext, envelopes, expires_at
                  FROM direct
                 WHERE room_id = ? AND id > ? AND expires_at > ?
                 ORDER BY id
                """,
                (rs, rowNum) -> new StoredDirect(
                        rs.getLong("id"),
                        rs.getBytes("ciphertext"),
                        rs.getBytes("envelopes"),
                        rs.getLong("expires_at")),
                roomId, lastSeenId, clock.millis());
    }

    public int sweepExpired() {
        return jdbc.update("DELETE FROM direct WHERE expires_at <= ?", clock.millis());
    }
}
