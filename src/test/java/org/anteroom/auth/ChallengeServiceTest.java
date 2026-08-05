package org.anteroom.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class ChallengeServiceTest {

    @TempDir
    Path dataDir;

    private MutableClock clock;
    private ServerKeyStore serverKeys;
    private ChallengeService challenges;
    private KeyPair device;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-05T12:00:00Z"));
        serverKeys = new ServerKeyStore(dataDir);
        challenges = new ChallengeService(serverKeys, clock);
        device = Ed25519Keys.newKeyPair();
    }

    private String signIssued(String nonce) {
        byte[] raw = Base64.getUrlDecoder().decode(nonce);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Ed25519Keys.sign(raw, device.getPrivate()));
    }

    private String devicePublicKey() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Ed25519Keys.rawPublicKey(device.getPublic()));
    }

    @Test
    void issuesThirtyTwoByteNonce() {
        String nonce = challenges.issue("10.0.0.1").nonce();

        assertThat(Base64.getUrlDecoder().decode(nonce)).hasSize(32);
    }

    @Test
    void neverRepeatsNonce() {
        // Адреса разные: на одном 200 вызовов упёрлись бы в лимит, и тест проверял бы его,
        // а не неповторяемость.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(challenges.issue("10.0." + (i / 250) + "." + i).nonce());
        }

        assertThat(seen).hasSize(200);
    }

    @Test
    void signsIssuedNonceWithServerKey() {
        // Клиент запоминает публичный ключ сервера и по этой подписи видит, что перед ним
        // тот же сервер. Без неё «подмена сервера» замечается только по факту.
        Challenge challenge = challenges.issue("10.0.0.1");

        assertThat(Ed25519Keys.verify(
                Base64.getUrlDecoder().decode(challenge.serverSignature()),
                Base64.getUrlDecoder().decode(challenge.nonce()),
                serverKeys.publicKey())).isTrue();
    }

    @Test
    void acceptsCorrectlySignedChallenge() {
        String nonce = challenges.issue("10.0.0.1").nonce();

        assertThat(challenges.redeem(nonce, devicePublicKey(), signIssued(nonce))).isTrue();
    }

    @Test
    void rejectsReplayOfUsedChallenge() {
        // Гашение при использовании обязательно: иначе подсмотренная пара nonce и подписи
        // пускает в комнату сколько угодно раз.
        String nonce = challenges.issue("10.0.0.1").nonce();
        String signature = signIssued(nonce);
        challenges.redeem(nonce, devicePublicKey(), signature);

        assertThat(challenges.redeem(nonce, devicePublicKey(), signature)).isFalse();
    }

    @Test
    void rejectsExpiredChallenge() {
        String nonce = challenges.issue("10.0.0.1").nonce();
        String signature = signIssued(nonce);

        clock.advance(ChallengeService.TTL.plusSeconds(1));

        assertThat(challenges.redeem(nonce, devicePublicKey(), signature)).isFalse();
    }

    @Test
    void acceptsChallengeJustBeforeDeadline() {
        String nonce = challenges.issue("10.0.0.1").nonce();
        String signature = signIssued(nonce);

        clock.advance(ChallengeService.TTL.minusSeconds(1));

        assertThat(challenges.redeem(nonce, devicePublicKey(), signature)).isTrue();
    }

    @Test
    void rejectsSignatureOfAnotherDevice() {
        String nonce = challenges.issue("10.0.0.1").nonce();
        String signature = signIssued(nonce);
        device = Ed25519Keys.newKeyPair();

        assertThat(challenges.redeem(nonce, devicePublicKey(), signature)).isFalse();
    }

    @Test
    void rejectsNonceItNeverIssued() {
        String stranger = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        assertThat(challenges.redeem(stranger, devicePublicKey(), signIssued(stranger))).isFalse();
    }

    @Test
    void rejectsMalformedInput() {
        // Всё это приходит из сети от кого угодно: разбор не должен падать наружу.
        assertThat(challenges.redeem("не base64!", devicePublicKey(), "тоже нет")).isFalse();
        assertThat(challenges.redeem(null, null, null)).isFalse();
    }

    @Test
    void stopsIssuingToOneAddressBeyondLimit() {
        for (int i = 0; i < ChallengeService.PER_IP_LIMIT; i++) {
            assertThat(challenges.issue("10.0.0.7")).isNotNull();
        }

        assertThat(challenges.issue("10.0.0.7")).isNull();
    }

    @Test
    void limitsAddressesSeparately() {
        for (int i = 0; i < ChallengeService.PER_IP_LIMIT; i++) {
            challenges.issue("10.0.0.7");
        }

        assertThat(challenges.issue("10.0.0.8")).isNotNull();
    }

    @Test
    void letsAddressInAgainAfterWindowPasses() {
        for (int i = 0; i < ChallengeService.PER_IP_LIMIT; i++) {
            challenges.issue("10.0.0.7");
        }
        clock.advance(ChallengeService.TTL.plusSeconds(1));

        assertThat(challenges.issue("10.0.0.7")).isNotNull();
    }

    @Test
    void keepsLiveNoncesUnderCeiling() {
        // Эндпоинт открыт всем, и без потолка он превращается в способ съесть память
        // сервера дешёвыми запросами с разных адресов.
        for (int i = 0; i < ChallengeService.MAX_LIVE + 100; i++) {
            challenges.issue("10.1." + (i / 250) + "." + (i % 250));
        }

        assertThat(challenges.liveCount()).isLessThanOrEqualTo(ChallengeService.MAX_LIVE);
    }

    @Test
    void forgetsExpiredNoncesWithoutRedeeming() {
        challenges.issue("10.0.0.1");
        clock.advance(ChallengeService.TTL.plusSeconds(1));
        challenges.issue("10.0.0.2");

        assertThat(challenges.liveCount()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
