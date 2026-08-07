package org.anteroom.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Счётчики попыток в памяти.
 *
 * <p>На диск не пишутся никогда: среди ключей есть IP-адреса, а держать их в базе — это
 * ровно тот журнал соединений, которого в этой системе быть не должно. Рестарт счётчики
 * обнуляет, и это приемлемо: злоупотребление стоит дороже перезапуска сервера.
 *
 * <p>Окно фиксированное, а не скользящее. Скользящее требует хранить историю попыток по
 * каждому ключу — то есть ровно ту память, которую лимитер и должен беречь.
 */
@Component
public class RateLimiter {

    /** Сколько ключей держать. Дальше уборка выбрасывает те, чьё окно уже прошло. */
    public static final int MAX_KEYS = 10_000;

    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock, @Value("${app.limit.max-keys:" + MAX_KEYS + "}") int maxKeys) {
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    /** @return {@code false}, если попытка уже сверх нормы; сама попытка всё равно считается */
    public boolean allow(String key, int limit, Duration window) {
        long now = clock.millis();
        forgetStale(now);

        Counter updated = counters.compute(key, (unused, current) ->
                current == null || now - current.startedAt() >= current.windowMillis()
                        ? new Counter(now, 1, window.toMillis())
                        : new Counter(current.startedAt(), current.count() + 1, current.windowMillis()));

        return updated.count() <= limit;
    }

    public int size() {
        return counters.size();
    }

    /**
     * Уборка не по расписанию, а когда ключей стало много: при спокойной работе она
     * не нужна вовсе, а при наплыве срабатывает сама.
     */
    private void forgetStale(long now) {
        if (counters.size() < maxKeys) {
            return;
        }
        counters.entrySet().removeIf(entry ->
                now - entry.getValue().startedAt() >= entry.getValue().windowMillis());
    }

    private record Counter(long startedAt, int count, long windowMillis) {
    }
}
