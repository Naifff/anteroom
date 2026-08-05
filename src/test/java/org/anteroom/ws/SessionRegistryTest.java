package org.anteroom.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class SessionRegistryTest {

    private final SessionRegistry registry = new SessionRegistry();

    @Test
    void keepsRegisteredSession() {
        WebSocketSession session = mock(WebSocketSession.class);

        registry.register("room-a", session);

        assertThat(registry.sessions("room-a")).containsExactly(session);
    }

    @Test
    void separatesRooms() {
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);

        registry.register("room-a", first);
        registry.register("room-b", second);

        assertThat(registry.sessions("room-a")).containsExactly(first);
        assertThat(registry.sessions("room-b")).containsExactly(second);
    }

    @Test
    void returnsEmptyForUnknownRoom() {
        assertThat(registry.sessions("no-such-room")).isEmpty();
    }

    @Test
    void dropsUnregisteredSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        registry.register("room-a", session);

        registry.unregister("room-a", session);

        assertThat(registry.sessions("room-a")).isEmpty();
    }

    @Test
    void forgetsRoomWithoutSessions() {
        // Иначе карта комнат растёт на каждую заглянувшую и ушедшую вкладку и не убывает
        // никогда: реестр живёт в памяти всё время работы процесса.
        WebSocketSession session = mock(WebSocketSession.class);
        registry.register("room-a", session);

        registry.unregister("room-a", session);

        assertThat(registry.roomCount()).isZero();
    }

    @Test
    void doesNotDuplicateSameSession() {
        // Реконнект вкладки не должен оставлять в комнате две ссылки на одну сессию:
        // иначе каждое сообщение уедет ей дважды.
        WebSocketSession session = mock(WebSocketSession.class);

        registry.register("room-a", session);
        registry.register("room-a", session);

        assertThat(registry.sessions("room-a")).hasSize(1);
    }

    @Test
    void survivesUnregisterOfUnknownSession() {
        // afterConnectionClosed прилетает и на сессию, которую зарегистрировать не успели.
        registry.unregister("room-a", mock(WebSocketSession.class));

        assertThat(registry.sessions("room-a")).isEmpty();
    }

    @Test
    void countsEverySessionUnderConcurrentRegistration() throws Exception {
        int threads = 16;
        List<WebSocketSession> sessions = IntStream.range(0, threads)
                .mapToObj(i -> mock(WebSocketSession.class))
                .toList();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> tasks = sessions.stream()
                    .map(session -> (Callable<Void>) () -> {
                        registry.register("room-a", session);
                        return null;
                    })
                    .toList();
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
        }

        assertThat(registry.sessions("room-a")).hasSize(threads);
    }
}
