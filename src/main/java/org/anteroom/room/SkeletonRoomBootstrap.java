package org.anteroom.room;

import java.time.Clock;

import org.anteroom.ws.RoomSocketHandler;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Заводит единственную комнату скелета.
 *
 * <p>Нужен ровно потому, что внешние ключи включены: без строки в {@code room} вставка
 * сообщения не пройдёт. Уйдёт вместе с фазой 5, когда комнаты начнут создаваться людьми.
 */
@Component
public class SkeletonRoomBootstrap {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SkeletonRoomBootstrap(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createSkeletonRoom() {
        jdbc.update("INSERT OR IGNORE INTO room (id, created_at) VALUES (?, ?)",
                RoomSocketHandler.SKELETON_ROOM, clock.millis());
    }
}
