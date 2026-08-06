package org.anteroom.ttl;

import java.time.Clock;

import org.anteroom.file.FileService;
import org.anteroom.message.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Уборка протухшего.
 *
 * <p>Sweeper — не то, на чём держится срок жизни: между тиками протухшие строки лежат
 * в базе, и не отдаёт их наружу фильтр при чтении. Задача sweeper'а — вернуть место
 * на диске, а не обеспечить приватность.
 *
 * <p>Прогон на старте обязателен: после долгого простоя база должна чиститься сразу,
 * а не через полминуты.
 */
@Component
public class SweeperJob {

    private static final Logger log = LoggerFactory.getLogger(SweeperJob.class);

    /** Сколько страниц возвращать за раз. Порциями, чтобы не держать базу на длинной уборке. */
    private static final int VACUUM_PAGES = 256;

    private final MessageService messages;
    private final FileService files;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SweeperJob(MessageService messages, FileService files, JdbcTemplate jdbc, Clock clock) {
        this.messages = messages;
        this.files = files;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 30_000)
    public void sweep() {
        long now = clock.millis();

        int deleted = messages.sweepExpired();
        // Вложения убирает FileService: у них есть вторая половина на диске, и порядок
        // «сначала блоб, потом строка» держится там.
        deleted += files.sweepExpired();
        deleted += jdbc.update("DELETE FROM onetime WHERE expires_at <= ?", now);
        // Исчерпанные инвайты уходят вместе с протухшими: пока строка жива, она держит
        // wrapped_key, и сохранённая кем-то ссылка остаётся заряженной.
        deleted += jdbc.update("DELETE FROM invite WHERE expires_at <= ? OR uses_left <= 0", now);

        if (deleted > 0) {
            log.debug("Удалено протухших строк: {}", deleted);
        }

        // Полный VACUUM не использовать: он блокирует базу целиком и требует вдвое больше
        // места на диске. incremental_vacuum отдаёт свободные страницы порциями.
        jdbc.execute("PRAGMA incremental_vacuum(" + VACUUM_PAGES + ")");
    }

    /**
     * Скан осиротевших блобов — только при старте, не каждые полминуты.
     *
     * <p>Сирота появляется от падения между записью тела и вставкой строки, а ещё от
     * удаления комнаты: каскад в схеме сносит строки, но про файловую систему не знает.
     * Оба случая редкие, а обход каталога стоит тем дороже, чем больше в нём файлов.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOrphanBlobs() {
        int removed = files.removeOrphans();
        if (removed > 0) {
            log.info("Удалено осиротевших блобов: {}", removed);
        }
    }
}
