package net.justempire.discordverificator.services;

import net.justempire.discordverificator.exceptions.InvalidCodeException;
import net.justempire.discordverificator.models.UsernameAndIp;
import net.justempire.discordverificator.utils.VerificationCodeGenerator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class ConfirmationCodeService {
    private static final long MIN_EXPIRATION_SECONDS = 30;
    private static final long MAX_EXPIRATION_SECONDS = 3_600;

    private record PendingVerification(
            String discordId,
            String usernameKey,
            UsernameAndIp data,
            Instant createdAt
    ) {
    }

    private final Map<String, PendingVerification> pendingByCode = new HashMap<>();
    private final Map<String, String> activeCodeByUsername = new HashMap<>();
    private final Clock clock;
    private Duration expiration;

    public ConfirmationCodeService(long expirationSeconds) {
        this(Duration.ofSeconds(clampExpirationSeconds(expirationSeconds)), Clock.systemUTC());
    }

    ConfirmationCodeService(Duration expiration, Clock clock) {
        if (expiration.isZero() || expiration.isNegative()) {
            throw new IllegalArgumentException("Code expiration must be positive");
        }
        this.expiration = expiration;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized String generateVerificationCode(String discordId, String username, String ip) {
        requireNonBlank(discordId, "discordId");
        requireNonBlank(username, "username");
        requireNonBlank(ip, "ip");
        purgeExpiredCodes();

        String usernameKey = username.toLowerCase(Locale.ROOT);
        String previousCode = activeCodeByUsername.remove(usernameKey);
        if (previousCode != null) {
            pendingByCode.remove(previousCode);
        }

        String code;
        String normalizedCode;
        do {
            code = VerificationCodeGenerator.generateVerificationCode();
            normalizedCode = normalizeGeneratedCode(code);
        } while (pendingByCode.containsKey(normalizedCode));

        PendingVerification pending = new PendingVerification(
                discordId,
                usernameKey,
                new UsernameAndIp(username, ip),
                clock.instant()
        );
        pendingByCode.put(normalizedCode, pending);
        activeCodeByUsername.put(usernameKey, normalizedCode);
        return code;
    }

    public synchronized UsernameAndIp getDataByCodeAndRemove(String code, String discordId) throws InvalidCodeException {
        String normalizedCode = normalizeSubmittedCode(code);
        PendingVerification pending = pendingByCode.get(normalizedCode);
        if (pending == null) {
            throw new InvalidCodeException();
        }

        if (isExpired(pending, clock.instant())) {
            removePendingCode(normalizedCode, pending);
            throw new InvalidCodeException();
        }

        if (!pending.discordId().equals(discordId)) {
            throw new InvalidCodeException();
        }

        removePendingCode(normalizedCode, pending);
        return pending.data();
    }

    public synchronized void updateExpirationSeconds(long expirationSeconds) {
        expiration = Duration.ofSeconds(clampExpirationSeconds(expirationSeconds));
        purgeExpiredCodes();
    }

    public synchronized int purgeExpiredCodes() {
        Instant now = clock.instant();
        int previousSize = pendingByCode.size();

        pendingByCode.entrySet().removeIf(entry -> {
            PendingVerification pending = entry.getValue();
            if (!isExpired(pending, now)) {
                return false;
            }
            activeCodeByUsername.remove(pending.usernameKey(), entry.getKey());
            return true;
        });

        return previousSize - pendingByCode.size();
    }

    public synchronized void clear() {
        pendingByCode.clear();
        activeCodeByUsername.clear();
    }

    synchronized int getActiveCodeCount() {
        return pendingByCode.size();
    }

    private boolean isExpired(PendingVerification pending, Instant now) {
        return !pending.createdAt().plus(expiration).isAfter(now);
    }

    private void removePendingCode(String normalizedCode, PendingVerification pending) {
        pendingByCode.remove(normalizedCode);
        activeCodeByUsername.remove(pending.usernameKey(), normalizedCode);
    }

    private static String normalizeSubmittedCode(String code) throws InvalidCodeException {
        if (code == null || code.isBlank()) {
            throw new InvalidCodeException();
        }
        return normalizeGeneratedCode(code.trim());
    }

    private static String normalizeGeneratedCode(String code) {
        return code.toLowerCase(Locale.ROOT);
    }

    private static long clampExpirationSeconds(long expirationSeconds) {
        return Math.max(MIN_EXPIRATION_SECONDS, Math.min(MAX_EXPIRATION_SECONDS, expirationSeconds));
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
