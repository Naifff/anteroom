package org.anteroom.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hashcash вместо капчи.
 *
 * <p>Капча просит человека доказать, что он человек, — и требует для этого стороннего
 * сервиса, то есть постороннего наблюдателя за каждым входом. Здесь вместо этого просят
 * потратить время процессора: одному человеку доля секунды на комнату незаметна, а тому,
 * кто заводит их тысячами, стоит ровно в тысячу раз дороже.
 *
 * <p>Соль выдаёт сервер и гасит её при предъявлении: без этого одна решённая задача
 * открывала бы сколько угодно комнат, а заготовленные заранее решения работали бы вечно.
 */
@Service
public class ProofOfWork {

    /**
     * Порог сложности в битах. 18 — это в среднем 260 тысяч хэшей: доля секунды в браузере
     * и заметная цена для того, кто повторяет это в цикле.
     */
    public static final int BITS = 18;

    public static final Duration TTL = Duration.ofMinutes(5);

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Clock clock;
    private final int bits;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> live = new ConcurrentHashMap<>();

    public ProofOfWork(Clock clock, @Value("${app.work.bits:" + BITS + "}") int bits) {
        this.clock = clock;
        this.bits = bits;
    }

    public Stamp issue() {
        long now = clock.millis();
        forgetExpired(now);

        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String salt = ENCODER.encodeToString(raw);
        live.put(salt, now + TTL.toMillis());

        return new Stamp(salt, bits);
    }

    /**
     * Проверяет решение и гасит соль.
     *
     * <p>Гашение до проверки, а не после: {@code remove} атомарен, и два одновременных
     * предъявления одной соли не пройдут оба. Проверить, а потом удалить — готовое окно
     * для повтора.
     */
    public boolean redeem(String salt, String counter) {
        if (salt == null || counter == null) {
            return false;
        }

        Long deadline = live.remove(salt);
        if (deadline == null || clock.millis() > deadline) {
            return false;
        }

        return leadingZeroBits(sha256(salt + ":" + counter)) >= bits;
    }

    /** Ноль означает «проверка выключена»: настройка для закрытого сервера. */
    public int bits() {
        return bits;
    }

    public int liveCount() {
        forgetExpired(clock.millis());
        return live.size();
    }

    private void forgetExpired(long now) {
        live.values().removeIf(deadline -> now > deadline);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("в этой JVM нет SHA-256", e);
        }
    }

    private static int leadingZeroBits(byte[] digest) {
        int bits = 0;
        for (byte value : digest) {
            if (value == 0) {
                bits += 8;
                continue;
            }
            // numberOfLeadingZeros считает по 32 битам, а байт занимает младшие восемь.
            return bits + Integer.numberOfLeadingZeros(value & 0xff) - 24;
        }
        return bits;
    }

    public record Stamp(String salt, int bits) {
    }
}
