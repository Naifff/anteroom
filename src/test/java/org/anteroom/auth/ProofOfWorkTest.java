package org.anteroom.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProofOfWorkTest {

    /** В тестах порог низкий: смысл проверок от него не зависит, а перебор занимает время. */
    private static final int BITS = 8;

    private MutableClock clock;
    private ProofOfWork work;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-06T12:00:00Z"));
        work = new ProofOfWork(clock, BITS);
    }

    @Test
    void acceptsAnHonestlySolvedStamp() {
        ProofOfWork.Stamp stamp = work.issue();

        assertThat(stamp.bits()).isEqualTo(BITS);
        assertThat(work.redeem(stamp.salt(), solve(stamp))).isTrue();
    }

    @Test
    void spendsTheStampOnce() {
        // Иначе одна решённая задача открывает сколько угодно комнат.
        ProofOfWork.Stamp stamp = work.issue();
        String counter = solve(stamp);
        work.redeem(stamp.salt(), counter);

        assertThat(work.redeem(stamp.salt(), counter)).isFalse();
    }

    @Test
    void refusesCounterThatDoesNotMeetTheTarget() {
        ProofOfWork.Stamp stamp = work.issue();

        assertThat(work.redeem(stamp.salt(), "0")).isFalse();
    }

    @Test
    void refusesUnknownSalt() {
        assertThat(work.redeem("такой не выдавали", "0")).isFalse();
    }

    @Test
    void refusesExpiredStamp() {
        ProofOfWork.Stamp stamp = work.issue();
        String counter = solve(stamp);

        clock.advance(ProofOfWork.TTL.plusSeconds(1));

        assertThat(work.redeem(stamp.salt(), counter)).isFalse();
    }

    @Test
    void refusesNulls() {
        assertThat(work.redeem(null, "0")).isFalse();
        ProofOfWork.Stamp stamp = work.issue();
        assertThat(work.redeem(stamp.salt(), null)).isFalse();
    }

    @Test
    void issuesADifferentSaltEveryTime() {
        assertThat(work.issue().salt()).isNotEqualTo(work.issue().salt());
    }

    @Test
    void spentStampDoesNotLingerInMemory() {
        ProofOfWork.Stamp stamp = work.issue();
        assertThat(work.liveCount()).isEqualTo(1);

        work.redeem(stamp.salt(), solve(stamp));

        assertThat(work.liveCount()).isZero();
    }

    /** Решает задачу тем же способом, каким её решает браузер: перебором счётчика. */
    private static String solve(ProofOfWork.Stamp stamp) {
        for (long counter = 0; counter < 10_000_000; counter++) {
            byte[] digest = sha256(stamp.salt() + ":" + counter);
            if (leadingZeroBits(digest) >= stamp.bits()) {
                return String.valueOf(counter);
            }
        }
        throw new AssertionError("не решилось");
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int leadingZeroBits(byte[] digest) {
        int bits = 0;
        for (byte value : digest) {
            if (value == 0) {
                bits += 8;
                continue;
            }
            return bits + Integer.numberOfLeadingZeros(value & 0xff) - 24;
        }
        return bits;
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
            return this;
        }
    }
}
