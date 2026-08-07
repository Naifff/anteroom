package org.anteroom.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private MutableClock clock;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-06T12:00:00Z"));
        limiter = new RateLimiter(clock, 8);
    }

    @Test
    void letsThroughExactlyTheAllowance() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.allow("send:устройство", 3, MINUTE)).as("попытка " + i).isTrue();
        }

        assertThat(limiter.allow("send:устройство", 3, MINUTE)).isFalse();
    }

    @Test
    void opensUpAgainInTheNextWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.allow("send:устройство", 3, MINUTE);
        }

        clock.advance(Duration.ofSeconds(61));

        assertThat(limiter.allow("send:устройство", 3, MINUTE)).isTrue();
    }

    @Test
    void keepsCountingWithinTheSameWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.allow("send:устройство", 3, MINUTE);
        }

        clock.advance(Duration.ofSeconds(59));

        assertThat(limiter.allow("send:устройство", 3, MINUTE)).isFalse();
    }

    @Test
    void countsEachKeyOnItsOwn() {
        for (int i = 0; i < 3; i++) {
            limiter.allow("send:первое", 3, MINUTE);
        }

        assertThat(limiter.allow("send:второе", 3, MINUTE))
                .as("сосед по счётчику не должен страдать")
                .isTrue();
    }

    @Test
    void forgetsKeysWhoseWindowHasPassed() {
        // Счётчики живут в памяти и на диск не пишутся. Значит память — это и есть ресурс,
        // который лимитер бережёт: без уборки он сам становится способом её съесть.
        for (int i = 0; i < 8; i++) {
            limiter.allow("устройство-" + i, 3, MINUTE);
        }
        assertThat(limiter.size()).isEqualTo(8);

        clock.advance(Duration.ofSeconds(61));
        limiter.allow("ещё-одно", 3, MINUTE);

        assertThat(limiter.size())
                .as("протухшие окна выброшены, осталось только свежее")
                .isEqualTo(1);
    }

    @Test
    void keepsLiveKeysWhenCrowded() {
        for (int i = 0; i < 8; i++) {
            limiter.allow("устройство-" + i, 3, MINUTE);
        }

        limiter.allow("ещё-одно", 3, MINUTE);

        assertThat(limiter.allow("устройство-0", 3, MINUTE))
                .as("живой счётчик не сбрасывается уборкой")
                .isTrue();
        assertThat(limiter.size()).isGreaterThan(1);
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
