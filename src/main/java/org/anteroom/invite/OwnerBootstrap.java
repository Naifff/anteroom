package org.anteroom.invite;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Owner-инвайт первого запуска.
 *
 * <p>Печатается в журнал <b>только если в базе нет ни одного владельца</b> и нет живого
 * owner-инвайта. Никакой формы регистрации админа, никакого повторного онбординга после
 * рестарта: иначе каждый перезапуск выбрасывал бы в журнал новую ссылку на владение сервером.
 *
 * <p>Это единственное место, где токен делает сервер, — просто потому, что клиента здесь
 * ещё нет. В базу, как и везде, ложится только хэш.
 */
@Component
public class OwnerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);
    private static final long TTL_SECONDS = 24 * 3600;

    private final JdbcTemplate jdbc;
    private final InviteService invites;
    private final String publicUrl;
    private final SecureRandom random = new SecureRandom();

    public OwnerBootstrap(JdbcTemplate jdbc, InviteService invites,
                          @Value("${app.public-url:http://localhost:8080}") String publicUrl) {
        this.jdbc = jdbc;
        this.invites = invites;
        this.publicUrl = publicUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        String token = ensureOwnerInvite();
        if (token == null) {
            return;
        }
        // Ссылка целиком уходит в stdout один раз. Дальше её нет нигде: в базе только хэш.
        log.warn("""

                Владелец ещё не назначен. Ссылка действительна 24 часа:
                {}/join#{}

                Откройте её в браузере. Второй раз она напечатана не будет.
                """, publicUrl, token);
    }

    /** Токен нового owner-инвайта либо {@code null}, если он не нужен. */
    public String ensureOwnerInvite() {
        Integer owners = jdbc.queryForObject("SELECT count(*) FROM instance_owner", Integer.class);
        if (owners != null && owners > 0) {
            return null;
        }
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM invite WHERE room_id IS NULL AND role = 'owner'", Integer.class);
        if (pending != null && pending > 0) {
            return null;
        }

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        // Обёртки ключа нет и быть не может: комнаты ещё нет, оборачивать нечего.
        invites.create(null, null, InviteHash.of(token), "owner", TTL_SECONDS, 1, new byte[0]);
        return token;
    }
}
