package org.anteroom.ttl;

import org.anteroom.message.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Уборка протухшего.
 *
 * <p>Sweeper — не то, на чём держится срок жизни: между тиками протухшие строки лежат
 * в базе, и не отдаёт их наружу фильтр при чтении в {@link MessageService#since}.
 * Задача sweeper'а — вернуть место на диске, а не обеспечить приватность.
 *
 * <p>Прогон на старте обязателен: после долгого простоя база должна чиститься до того,
 * как сервер начнёт отвечать, а не через десять секунд после.
 */
@Component
public class SweeperJob {

    private static final Logger log = LoggerFactory.getLogger(SweeperJob.class);

    private final MessageService messages;

    public SweeperJob(MessageService messages) {
        this.messages = messages;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 10_000)
    public void sweep() {
        int deleted = messages.sweepExpired();
        if (deleted > 0) {
            log.debug("Удалено протухших сообщений: {}", deleted);
        }
    }
}
