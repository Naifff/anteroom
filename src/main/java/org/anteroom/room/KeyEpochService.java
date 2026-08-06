package org.anteroom.room;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Эпохи ключа комнаты и обёртки под каждого участника.
 *
 * <p>Сервер хранит только обёртки: сам {@code room_key} он не видит никогда. Обёртка —
 * sealed box под X25519 участника, разворачивается только у него в браузере.
 *
 * <p>Double Ratchet не используется, forward secrecy держится на ротации эпох. При удалении
 * участника ротация обязательна: старую историю удалённый расшифровать сможет, новую нет.
 */
@Service
public class KeyEpochService {

    private final JdbcTemplate jdbc;

    public KeyEpochService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Кладёт обёртку, заменяя прежнюю той же эпохи: перевыпуск не должен плодить строки. */
    public void storeWrappedKey(String roomId, String pubkeySign, int epoch, byte[] wrapped) {
        jdbc.update("""
                INSERT INTO member_key (room_id, pubkey_sign, epoch, wrapped_room_key) VALUES (?, ?, ?, ?)
                ON CONFLICT (room_id, pubkey_sign, epoch) DO UPDATE SET wrapped_room_key = excluded.wrapped_room_key
                """, roomId, pubkeySign, epoch, wrapped);
    }

    public byte[] wrappedKey(String roomId, String pubkeySign, int epoch) {
        return jdbc.query("""
                SELECT wrapped_room_key FROM member_key
                 WHERE room_id = ? AND pubkey_sign = ? AND epoch = ?
                """,
                rs -> rs.next() ? rs.getBytes(1) : null, roomId, pubkeySign, epoch);
    }

    /**
     * Публикует ключ личной переписки на эпоху.
     *
     * <p>Отдельно от {@code member_key}: обёртку ключа комнаты кладёт другой участник,
     * а этот ключ человек объявляет сам, и порядок этих двух событий не задан.
     *
     * <p>Ключ свой на каждую эпоху не для красоты: X25519 устройства живёт вечно, и его
     * утечка раскрыла бы всю личную переписку задним числом — ротации ключа комнаты
     * долгоживущий ключ не подчиняется.
     */
    public void storeDmKey(String roomId, String pubkeySign, int epoch, String pubkeyDm) {
        jdbc.update("""
                INSERT INTO dm_key (room_id, pubkey_sign, epoch, pubkey_dm) VALUES (?, ?, ?, ?)
                ON CONFLICT (room_id, pubkey_sign, epoch) DO UPDATE SET pubkey_dm = excluded.pubkey_dm
                """, roomId, pubkeySign, epoch, pubkeyDm);
    }

    /** Ключи переписки этой эпохи: устройство — ключ. Кого нет, тому написать нельзя. */
    public Map<String, String> dmKeys(String roomId, int epoch) {
        Map<String, String> keys = new HashMap<>();
        jdbc.query("SELECT pubkey_sign, pubkey_dm FROM dm_key WHERE room_id = ? AND epoch = ?",
                rs -> {
                    keys.put(rs.getString("pubkey_sign"), rs.getString("pubkey_dm"));
                }, roomId, epoch);
        return keys;
    }

    /**
     * Поднимает эпоху и возвращает новую.
     *
     * <p>Обёртки прежних эпох остаются: оставшиеся участники обязаны дочитать историю,
     * которая ещё не протухла, а ключа от неё взять больше неоткуда.
     */
    @Transactional
    public int rotate(String roomId) {
        jdbc.update("UPDATE room SET key_epoch = key_epoch + 1 WHERE id = ?", roomId);
        return jdbc.queryForObject("SELECT key_epoch FROM room WHERE id = ?", Integer.class, roomId);
    }

    /**
     * Кому обёртки этой эпохи ещё не положили.
     *
     * <p>Вышедшие сюда не попадают: не выдать им ключ новой эпохи — и есть смысл ротации.
     */
    public List<String> membersWithoutWrapper(String roomId, int epoch) {
        return jdbc.queryForList("""
                SELECT m.pubkey_sign
                  FROM member m
                 WHERE m.room_id = ? AND m.left_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM member_key k
                                    WHERE k.room_id = m.room_id
                                      AND k.pubkey_sign = m.pubkey_sign
                                      AND k.epoch = ?)
                 ORDER BY m.card
                """, String.class, roomId, epoch);
    }
}
