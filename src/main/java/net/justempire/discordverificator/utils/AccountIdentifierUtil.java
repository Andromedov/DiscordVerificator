package net.justempire.discordverificator.utils;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AccountIdentifierUtil {
    public enum TargetType {
        MINECRAFT_USERNAME,
        DISCORD_ID
    }

    public record ParsedTarget(TargetType type, String value) {
    }

    private static final Pattern MINECRAFT_USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern DISCORD_ID_PATTERN =
            Pattern.compile("^\\d{17,20}$");

    private AccountIdentifierUtil() {
    }

    public static Optional<String> parseMinecraftUsername(String input) {
        String normalized = trim(input);
        if (normalized == null || !MINECRAFT_USERNAME_PATTERN.matcher(normalized).matches()) {
            return Optional.empty();
        }
        return Optional.of(normalized.toLowerCase(Locale.ROOT));
    }

    public static Optional<String> parseDiscordId(String input) {
        String normalized = trim(input);
        if (normalized == null || !DISCORD_ID_PATTERN.matcher(normalized).matches()) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    public static Optional<ParsedTarget> parseTarget(String input) {
        Optional<String> discordId = parseDiscordId(input);
        if (discordId.isPresent()) {
            return Optional.of(new ParsedTarget(TargetType.DISCORD_ID, discordId.get()));
        }

        return parseMinecraftUsername(input)
                .map(username -> new ParsedTarget(TargetType.MINECRAFT_USERNAME, username));
    }

    private static String trim(String input) {
        return input == null ? null : input.trim();
    }
}
