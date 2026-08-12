package org.anteroom.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Выдача и гашение вызовов на вход.
 *
 * <p>Живут в памяти и рестарт переживать не должны: клиент всегда может попросить новый.
 *
 * <p>Эндпоинт выдачи открыт всем без исключения, поэтому у него два потолка сразу —
 * на адрес и на общее число живых вызовов. Без них он превращается в способ съесть
 * память сервера очень дешёвыми запросами.
 */
@Service
public class ChallengeService {

    public static final Duration TTL = Duration.ofSeconds(60);
    /** Значения по умолчанию. Настраиваются, потому что нагрузка у всех разная. */
    public static final int PER_IP_LIMIT = 30;
    public static final int MAX_LIVE = 10000;
    /**
     * Потолок на число адресов в окне.
     *
     * <p>Отдельный от {@link #MAX_LIVE}: тот считает выданные вызовы, а запись в карте
     * адресов заводится и на отказанный запрос. Упёршийся в {@code MAX_LIVE} сервер
     * не выдаёт ничего и при этом продолжал бы копить адреса — потолок на живые вызовы
     * от этого не спасает вовсе.
     */
    public static final int MAX_ADDRESSES = 20000;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> live = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();

    private final ServerKeyStore serverKeys;
    private final Clock clock;
    private final int perIpLimit;
    private final int maxLive;
    private final int maxAddresses;
    private volatile long windowStartedAt;

    public ChallengeService(ServerKeyStore serverKeys, Clock clock,
                            @Value("${app.challenge.per-ip-limit:" + PER_IP_LIMIT + "}") int perIpLimit,
                            @Value("${app.challenge.max-live:" + MAX_LIVE + "}") int maxLive,
                            @Value("${app.challenge.max-addresses:" + MAX_ADDRESSES + "}") int maxAddresses) {
        this.serverKeys = serverKeys;
        this.clock = clock;
        this.perIpLimit = perIpLimit;
        this.maxLive = maxLive;
        this.maxAddresses = maxAddresses;
        this.windowStartedAt = clock.millis();
    }

    /** Вызов либо {@code null}, если адрес исчерпал лимит или сервер уже держит потолок. */
    public Challenge issue(String clientIp) {
        long now = clock.millis();
        forgetExpired(now);
        rollWindow(now);

        // Место под новый адрес проверяется до того, как он заведён. Отказ достаётся
        // только незнакомым адресам: если гасить всех подряд, наплыв с чужих адресов
        // выбивает тех, кто уже работает, и потолок памяти превращается в способ отказа
        // в обслуживании. Гонка здесь безобидна — перебор на несколько записей.
        AtomicInteger seen = perIp.get(clientIp);
        if (seen == null) {
            if (perIp.size() >= maxAddresses) {
                return null;
            }
            seen = perIp.computeIfAbsent(clientIp, ip -> new AtomicInteger());
        }
        if (seen.incrementAndGet() > perIpLimit) {
            return null;
        }
        if (live.size() >= maxLive) {
            return null;
        }

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String nonce = ENCODER.encodeToString(raw);
        live.put(nonce, now + TTL.toMillis());

        return new Challenge(
                nonce,
                ENCODER.encodeToString(serverKeys.sign(raw)),
                ENCODER.encodeToString(serverKeys.publicKey()));
    }

    /**
     * Проверяет подпись под выданным вызовом и гасит его.
     *
     * <p>Гашение до проверки подписи, а не после: {@code remove} атомарен, и два
     * одновременных запроса с одной парой не пройдут оба. Проверять, а потом удалять —
     * готовое окно для повтора.
     */
    public boolean redeem(String nonce, String devicePublicKey, String signature) {
        if (nonce == null || devicePublicKey == null || signature == null) {
            return false;
        }

        Long deadline = live.remove(nonce);
        if (deadline == null || clock.millis() > deadline) {
            return false;
        }

        try {
            return Ed25519Keys.verify(
                    DECODER.decode(signature), DECODER.decode(nonce), DECODER.decode(devicePublicKey));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public int liveCount() {
        forgetExpired(clock.millis());
        return live.size();
    }

    /** Сколько адресов посчитано в текущем окне. */
    public int addressCount() {
        return perIp.size();
    }

    private void forgetExpired(long now) {
        live.values().removeIf(deadline -> now > deadline);
    }

    /**
     * Счётчики по адресам обнуляются целиком раз в окно, а не скользящим средним:
     * хранить историю запросов по каждому адресу — это ровно та память, которую
     * лимит и должен беречь.
     */
    private void rollWindow(long now) {
        if (now - windowStartedAt >= TTL.toMillis()) {
            windowStartedAt = now;
            perIp.clear();
        }
    }
}
