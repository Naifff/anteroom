package org.anteroom.ws;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Кто сейчас в какой комнате. Живёт только в памяти и переживать рестарт не должен:
 * клиент переподключается сам, подписывает challenge и добирает пропущенное по {@code since}.
 */
@Component
public class SessionRegistry {

    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public void register(String roomId, WebSocketSession session) {
        rooms.computeIfAbsent(roomId, id -> ConcurrentHashMap.newKeySet()).add(session);
    }

    /**
     * Снимает сессию и убирает опустевшую комнату.
     *
     * <p>{@code compute} здесь не роскошь: между «удалили последнюю сессию» и «удалили комнату»
     * другой поток успевает войти в ту же комнату, и наивная пара операций выбросила бы его
     * из реестра. Внутри {@code compute} карта держит бакет заблокированным.
     */
    public void unregister(String roomId, WebSocketSession session) {
        rooms.computeIfPresent(roomId, (id, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    public Set<WebSocketSession> sessions(String roomId) {
        Set<WebSocketSession> sessions = rooms.get(roomId);
        return sessions == null ? Set.of() : Collections.unmodifiableSet(sessions);
    }

    public int roomCount() {
        return rooms.size();
    }
}
