package org.anteroom.room;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Комнаты и участие в них.
 *
 * <p>Сроки жизни зажимаются здесь и только здесь. Значение приходит от клиента, поэтому
 * верить ему нельзя: сервер приводит присланное к допустимому диапазону молча, а не
 * отвергает, — кроме совсем бессмысленных значений ниже пола.
 */
@Service
public class RoomService {

    /** Потолок срока жизни сообщения, пять суток. Не поднимать, не перечитав CLAUDE.md. */
    public static final long TTL_CEILING = 432_000;
    public static final long TTL_FLOOR = 60;
    public static final long TTL_DEFAULT = 172_800;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RoomService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public String create(String ownerPubkey, long defaultTtl, long maxTtl) {
        if (maxTtl < TTL_FLOOR || defaultTtl < TTL_FLOOR) {
            throw new IllegalArgumentException("срок жизни меньше " + TTL_FLOOR + " секунд бессмыслен");
        }

        long ceiling = Math.min(maxTtl, TTL_CEILING);
        long fallback = Math.min(defaultTtl, ceiling);

        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String roomId = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        jdbc.update("""
                INSERT INTO room (id, key_epoch, default_ttl, max_ttl, seats_taken, created_at)
                VALUES (?, 1, ?, ?, 0, ?)
                """, roomId, fallback, ceiling, clock.millis());

        join(roomId, ownerPubkey, "owner", null);
        return roomId;
    }

    /**
     * Сажает устройство в комнату и отдаёт его карту.
     *
     * <p>Повторный вход тем же ключом карту не меняет и колоду не тратит: реконнект
     * и восстановление из слов — не новый участник.
     */
    @Transactional
    public int join(String roomId, String pubkeySign, String role, String invitedBy) {
        Integer existing = jdbc.query(
                "SELECT card FROM member WHERE room_id = ? AND pubkey_sign = ?",
                rs -> rs.next() ? rs.getInt(1) : null, roomId, pubkeySign);
        if (existing != null) {
            return existing;
        }

        // Занятыми считаются и карты вышедших: выбывшая карта в колоду не возвращается.
        Set<Integer> taken = new HashSet<>(
                jdbc.queryForList("SELECT card FROM member WHERE room_id = ?", Integer.class, roomId));
        int card = CardDealer.deal(roomId, pubkeySign, taken);

        jdbc.update("""
                INSERT INTO member (room_id, pubkey_sign, role, card, invited_by, joined_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, roomId, pubkeySign, role, card, invitedBy, clock.millis());
        jdbc.update("UPDATE room SET seats_taken = seats_taken + 1 WHERE id = ?", roomId);

        return card;
    }

    /**
     * Убирает участника, оставляя строку и карту.
     *
     * <p>Строка нужна, чтобы карта осталась занятой навсегда, иначе через неделю она
     * достанется другому человеку, а старые реплики прежнего владельца останутся в ленте.
     */
    public void remove(String roomId, String pubkeySign) {
        jdbc.update("UPDATE member SET left_at = ? WHERE room_id = ? AND pubkey_sign = ? AND left_at IS NULL",
                clock.millis(), roomId, pubkeySign);
    }

    public Room find(String roomId) {
        return jdbc.queryForObject("""
                SELECT id, key_epoch, default_ttl, max_ttl, seats_taken, created_at FROM room WHERE id = ?
                """,
                (rs, rowNum) -> new Room(
                        rs.getString("id"),
                        rs.getInt("key_epoch"),
                        rs.getLong("default_ttl"),
                        rs.getLong("max_ttl"),
                        rs.getInt("seats_taken"),
                        rs.getLong("created_at")),
                roomId);
    }

    public List<Member> members(String roomId) {
        return jdbc.query("""
                SELECT pubkey_sign, role, card, invited_by, joined_at
                  FROM member
                 WHERE room_id = ? AND left_at IS NULL
                 ORDER BY card
                """,
                (rs, rowNum) -> new Member(
                        rs.getString("pubkey_sign"),
                        rs.getString("role"),
                        rs.getInt("card"),
                        rs.getString("invited_by"),
                        rs.getLong("joined_at")),
                roomId);
    }

    public String role(String roomId, String pubkeySign) {
        return jdbc.query("SELECT role FROM member WHERE room_id = ? AND pubkey_sign = ? AND left_at IS NULL",
                rs -> rs.next() ? rs.getString(1) : null, roomId, pubkeySign);
    }

    public int card(String roomId, String pubkeySign) {
        Integer card = jdbc.query("SELECT card FROM member WHERE room_id = ? AND pubkey_sign = ?",
                rs -> rs.next() ? rs.getInt(1) : null, roomId, pubkeySign);
        if (card == null) {
            throw new IllegalArgumentException("устройства нет в комнате");
        }
        return card;
    }
}
