package org.anteroom.invite;

import java.time.Clock;

import org.anteroom.Refusal;
import org.anteroom.device.DeviceService;
import org.anteroom.room.RoomService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Приглашения.
 *
 * <p><b>Сам токен на сервер не попадает при выпуске.</b> Его делает клиент и присылает
 * только SHA-256; в базе, как и требует модель угроз, лежит хэш. При погашении токен
 * приходит целиком — иначе хранение хэша теряет смысл: предъявившему хэш открывать
 * комнату нельзя, а прочитать базу проще, чем перехватить переход по ссылке.
 *
 * <p><b>В ссылке едет не ключ комнаты, а эфемерный приватный ключ инвайта.</b> Сам
 * {@code room_key}, обёрнутый под эфемерный публичный, лежит здесь в {@code wrapped_key}
 * и исчезает вместе с исчерпанным инвайтом. Иначе пересланная в мессенджер ссылка навсегда
 * остаётся ключом от комнаты — а превью-боты и архивы такие ссылки видят.
 */
@Service
public class InviteService {

    private final JdbcTemplate jdbc;
    private final RoomService rooms;
    private final DeviceService devices;
    private final Clock clock;

    public InviteService(JdbcTemplate jdbc, RoomService rooms, DeviceService devices, Clock clock) {
        this.jdbc = jdbc;
        this.rooms = rooms;
        this.devices = devices;
        this.clock = clock;
    }

    /**
     * @throws Refusal когда в комнате не осталось мест: приглашение туда — обещание,
     *         которое сервер не выполнит, и отказ должен достаться выпускающему, а не
     *         приглашённому. Иначе владелец отправляет ссылку человеку, тот открывает её
     *         и упирается в конец колоды, ничего не в силах сделать.
     */
    public void create(String roomId, String issuer, String tokenHash, String role,
                       long ttlSeconds, int uses, byte[] wrappedKey) {
        // Owner-инвайт первого запуска идёт без комнаты — проверять нечего.
        if (roomId != null && rooms.find(roomId).deckSpent()) {
            throw new Refusal(Refusal.DECK_SPENT);
        }
        jdbc.update("""
                INSERT INTO invite (token_hash, room_id, role, created_by, wrapped_key, expires_at, uses_left)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tokenHash, roomId, role, issuer, wrappedKey, clock.millis() + ttlSeconds * 1000, uses);
    }

    /**
     * Гасит инвайт и сажает устройство в комнату.
     *
     * <p>Списание попытки — одним {@code UPDATE} с условием прямо в запросе, а не
     * «прочитали, проверили, записали»: на два одновременных перехода по одноразовой ссылке
     * второй должен получить нулевое число изменённых строк, а не увидеть ещё не списанный
     * счётчик. SQLite сериализует запись, поэтому этого достаточно.
     */
    @Transactional
    public Redemption redeem(String token, String pubkeySign) {
        String tokenHash = InviteHash.of(token);

        int spent = jdbc.update("""
                UPDATE invite SET uses_left = uses_left - 1
                 WHERE token_hash = ? AND uses_left > 0 AND expires_at > ?
                """, tokenHash, clock.millis());
        if (spent == 0) {
            return Redemption.refused();
        }

        Invitation invitation;
        try {
            invitation = jdbc.queryForObject("""
                    SELECT room_id, role, created_by, wrapped_key, uses_left FROM invite WHERE token_hash = ?
                    """,
                    (rs, rowNum) -> new Invitation(
                            rs.getString("room_id"), rs.getString("role"),
                            rs.getString("created_by"), rs.getBytes("wrapped_key"), rs.getInt("uses_left")),
                    tokenHash);
        } catch (EmptyResultDataAccessException e) {
            return Redemption.refused();
        }

        if (invitation.roomId() == null) {
            // Инвайт без комнаты — owner-инвайт первого запуска: комнаты ещё нет, сажать
            // некуда, выдаётся право заводить их.
            devices.grantInstanceOwnership(pubkeySign);
        } else {
            rooms.join(invitation.roomId(), pubkeySign, invitation.role(), invitation.issuer());
        }

        if (invitation.usesLeft() <= 0) {
            // Вместе со строкой уходит и wrapped_key: с этого момента ссылка бесполезна,
            // даже если её сохранили.
            jdbc.update("DELETE FROM invite WHERE token_hash = ?", tokenHash);
        }

        return Redemption.accepted(invitation.roomId(), invitation.role(), invitation.wrappedKey());
    }

    public void revoke(String roomId, String tokenHash) {
        jdbc.update("DELETE FROM invite WHERE room_id = ? AND token_hash = ?", roomId, tokenHash);
    }

    /** Каскадный отзыв — по явной команде, а не автоматом при исключении участника. */
    public void revokeIssuedBy(String roomId, String issuer) {
        jdbc.update("DELETE FROM invite WHERE room_id = ? AND created_by = ?", roomId, issuer);
    }

    private record Invitation(String roomId, String role, String issuer, byte[] wrappedKey, int usesLeft) {
    }
}
