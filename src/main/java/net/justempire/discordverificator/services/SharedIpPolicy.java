package net.justempire.discordverificator.services;

public final class SharedIpPolicy {
    private SharedIpPolicy() {
    }

    public static int normalizeMaximumAccounts(int configuredMaximum) {
        return Math.max(1, configuredMaximum);
    }

    public static boolean isLimitExceeded(
            int otherDiscordAccountsOnIp,
            int maximumDiscordAccountsPerIp,
            boolean sharedIpBypassAllowed
    ) {
        if (otherDiscordAccountsOnIp < 0) {
            throw new IllegalArgumentException("Other Discord account count cannot be negative");
        }
        if (sharedIpBypassAllowed) {
            return false;
        }

        int normalizedMaximum = normalizeMaximumAccounts(maximumDiscordAccountsPerIp);
        int totalDiscordAccountsOnIp = otherDiscordAccountsOnIp + 1;
        return totalDiscordAccountsOnIp > normalizedMaximum;
    }
}
