package net.justempire.discordverificator.services;

import net.justempire.discordverificator.exceptions.InvalidCodeException;
import net.justempire.discordverificator.models.UsernameAndIp;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationCodeServiceTest {
    @Test
    void codeIsCaseInsensitiveSingleUseAndBoundToDiscordAccount() throws Exception {
        ConfirmationCodeService service = new ConfirmationCodeService(300, 8);
        String code = service.generateVerificationCode("111", "PlayerOne", "127.0.0.1");

        assertEquals(8, code.length());
        assertTrue(code.matches("[A-HJ-NP-Z2-9]{8}"));
        assertThrows(InvalidCodeException.class,
                () -> service.getDataByCodeAndRemove(code, "222"));

        UsernameAndIp data = service.getDataByCodeAndRemove(code.toLowerCase(), "111");
        assertEquals("PlayerOne", data.getUsername());
        assertEquals("127.0.0.1", data.getIpAddress());
        assertThrows(InvalidCodeException.class,
                () -> service.getDataByCodeAndRemove(code, "111"));
    }

    @Test
    void issuingNewCodeInvalidatesPreviousCodeForSameUsername() throws Exception {
        ConfirmationCodeService service = new ConfirmationCodeService(300);
        String oldCode = service.generateVerificationCode("111", "PlayerOne", "127.0.0.1");
        String newCode = service.generateVerificationCode("111", "playerone", "127.0.0.2");

        assertEquals(1, service.getActiveCodeCount());
        assertThrows(InvalidCodeException.class,
                () -> service.getDataByCodeAndRemove(oldCode, "111"));
        assertEquals("127.0.0.2",
                service.getDataByCodeAndRemove(newCode, "111").getIpAddress());
    }

    @Test
    void expiredCodesAreRejectedAndPurged() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T00:00:00Z"));
        ConfirmationCodeService service = new ConfirmationCodeService(Duration.ofMinutes(5), clock);
        String code = service.generateVerificationCode("111", "PlayerOne", "127.0.0.1");

        clock.advance(Duration.ofMinutes(5));

        assertThrows(InvalidCodeException.class,
                () -> service.getDataByCodeAndRemove(code, "111"));
        assertEquals(0, service.getActiveCodeCount());
    }

    @Test
    void concurrentConsumeSucceedsExactlyOnce() throws Exception {
        ConfirmationCodeService service = new ConfirmationCodeService(300);
        String code = service.generateVerificationCode("111", "PlayerOne", "127.0.0.1");
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                attempts.add(() -> {
                    try {
                        service.getDataByCodeAndRemove(code, "111");
                        return true;
                    } catch (InvalidCodeException e) {
                        return false;
                    }
                });
            }

            int successfulConsumes = 0;
            for (Future<Boolean> result : executor.invokeAll(attempts)) {
                if (result.get()) {
                    successfulConsumes++;
                }
            }
            assertEquals(1, successfulConsumes);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void codeLengthCanBeReloadedWithoutInvalidatingActiveCodes() throws Exception {
        ConfirmationCodeService service = new ConfirmationCodeService(300, 5);
        String existingCode = service.generateVerificationCode("111", "PlayerOne", "127.0.0.1");

        service.updateSettings(300, 12);
        String newCode = service.generateVerificationCode("222", "PlayerTwo", "127.0.0.2");

        assertEquals(5, existingCode.length());
        assertEquals(12, newCode.length());
        assertEquals("PlayerOne", service.getDataByCodeAndRemove(existingCode, "111").getUsername());
        assertEquals("PlayerTwo", service.getDataByCodeAndRemove(newCode, "222").getUsername());
    }

    @Test
    void codeLengthIsClampedToSafeConfigurationRange() {
        ConfirmationCodeService service = new ConfirmationCodeService(300, 1);
        String minimumLengthCode = service.generateVerificationCode("111", "PlayerOne", "127.0.0.1");

        service.updateSettings(300, 100);
        String maximumLengthCode = service.generateVerificationCode("222", "PlayerTwo", "127.0.0.2");

        assertEquals(ConfirmationCodeService.MIN_CODE_LENGTH, minimumLengthCode.length());
        assertEquals(ConfirmationCodeService.MAX_CODE_LENGTH, maximumLengthCode.length());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
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
