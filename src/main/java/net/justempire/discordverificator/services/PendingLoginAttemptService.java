package net.justempire.discordverificator.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class PendingLoginAttemptService {
    public record PendingLoginAttempt(String minecraftUsername, String ipAddress, Instant attemptedAt) {
    }

    private static final Duration DEFAULT_EXPIRATION = Duration.ofMinutes(5);

    private final Map<String, PendingLoginAttempt> attempts = new ConcurrentHashMap<>();
    private final Duration expiration;
    private final Clock clock;

    public PendingLoginAttemptService() {
        this(DEFAULT_EXPIRATION, Clock.systemUTC());
    }

    PendingLoginAttemptService(Duration expiration, Clock clock) {
        if (expiration.isZero() || expiration.isNegative()) {
            throw new IllegalArgumentException("Pending login expiration must be positive");
        }
        this.expiration = expiration;
        this.clock = clock;
    }

    public void recordAttempt(String minecraftUsername, String ipAddress) {
        if (minecraftUsername == null || minecraftUsername.isBlank()) {
            throw new IllegalArgumentException("Minecraft username cannot be blank");
        }
        if (ipAddress == null || ipAddress.isBlank()) {
            throw new IllegalArgumentException("IP address cannot be blank");
        }

        purgeExpired();
        attempts.put(normalize(minecraftUsername),
                new PendingLoginAttempt(minecraftUsername, ipAddress, clock.instant()));
    }

    public Optional<PendingLoginAttempt> claimRecentAttempt(String minecraftUsername) {
        if (minecraftUsername == null) {
            return Optional.empty();
        }

        PendingLoginAttempt attempt = attempts.remove(normalize(minecraftUsername));
        if (attempt == null || isExpired(attempt, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(attempt);
    }

    public void purgeExpired() {
        Instant now = clock.instant();
        attempts.values().removeIf(attempt -> isExpired(attempt, now));
    }

    public void clear() {
        attempts.clear();
    }

    private boolean isExpired(PendingLoginAttempt attempt, Instant now) {
        return !attempt.attemptedAt().plus(expiration).isAfter(now);
    }

    private static String normalize(String minecraftUsername) {
        return minecraftUsername.trim().toLowerCase(Locale.ROOT);
    }
}
