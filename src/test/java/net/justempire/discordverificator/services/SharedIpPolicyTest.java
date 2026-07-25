package net.justempire.discordverificator.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedIpPolicyTest {
    @Test
    void allowsAccountAtConfiguredBoundary() {
        assertFalse(SharedIpPolicy.isLimitExceeded(0, 1, false));
        assertFalse(SharedIpPolicy.isLimitExceeded(2, 3, false));
    }

    @Test
    void blocksAccountAboveConfiguredBoundary() {
        assertTrue(SharedIpPolicy.isLimitExceeded(1, 1, false));
        assertTrue(SharedIpPolicy.isLimitExceeded(3, 3, false));
    }

    @Test
    void explicitBypassIgnoresNumericLimit() {
        assertFalse(SharedIpPolicy.isLimitExceeded(100, 1, true));
    }

    @Test
    void invalidConfigurationIsNormalizedAndInvalidCountsAreRejected() {
        assertEquals(1, SharedIpPolicy.normalizeMaximumAccounts(0));
        assertEquals(1, SharedIpPolicy.normalizeMaximumAccounts(-10));
        assertEquals(5, SharedIpPolicy.normalizeMaximumAccounts(5));
        assertThrows(IllegalArgumentException.class,
                () -> SharedIpPolicy.isLimitExceeded(-1, 1, false));
    }
}
