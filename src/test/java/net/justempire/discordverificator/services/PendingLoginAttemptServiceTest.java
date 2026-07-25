package net.justempire.discordverificator.services;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingLoginAttemptServiceTest {
    @Test
    void claimsLatestAttemptOnceAndIgnoresUsernameCase() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-25T10:00:00Z"));
        PendingLoginAttemptService service =
                new PendingLoginAttemptService(Duration.ofMinutes(5), clock);

        service.recordAttempt("TestPlayer", "192.0.2.10");
        clock.advance(Duration.ofSeconds(10));
        service.recordAttempt("testplayer", "192.0.2.20");

        PendingLoginAttemptService.PendingLoginAttempt attempt =
                service.claimRecentAttempt("TESTPLAYER").orElseThrow();
        assertEquals("192.0.2.20", attempt.ipAddress());
        assertTrue(service.claimRecentAttempt("TestPlayer").isEmpty());
    }

    @Test
    void expiredAttemptCannotBeConfirmed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-25T10:00:00Z"));
        PendingLoginAttemptService service =
                new PendingLoginAttemptService(Duration.ofMinutes(5), clock);
        service.recordAttempt("TestPlayer", "198.51.100.25");

        clock.advance(Duration.ofMinutes(5));

        assertTrue(service.claimRecentAttempt("TestPlayer").isEmpty());
    }

    @Test
    void rejectsInvalidExpirationAndAttemptData() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-25T10:00:00Z"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PendingLoginAttemptService(Duration.ZERO, clock)
        );

        PendingLoginAttemptService service =
                new PendingLoginAttemptService(Duration.ofMinutes(5), clock);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.recordAttempt("", "192.0.2.1")
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.recordAttempt("TestPlayer", " ")
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
