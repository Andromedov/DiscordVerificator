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

    public static final String DEFAULT_BEDROCK_USERNAME_PREFIX = ".";

    private static final Pattern JAVA_USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern BEDROCK_USERNAME_BODY_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern DISCORD_ID_PATTERN =
            Pattern.compile("^\\d{17,20}$");

    private AccountIdentifierUtil() {
    }

    public static Optional<String> parseMinecraftUsername(String input) {
        return parseMinecraftUsername(input, DEFAULT_BEDROCK_USERNAME_PREFIX);
    }

    public static Optional<String> parseJavaUsername(String input) {
        String normalized = trim(input);
        if (normalized == null || !JAVA_USERNAME_PATTERN.matcher(normalized).matches()) {
            return Optional.empty();
        }
        return Optional.of(normalized.toLowerCase(Locale.ROOT));
    }

    public static Optional<String> parseMinecraftUsername(String input, String bedrockUsernamePrefix) {
        String normalized = trim(input);
        if (normalized == null) {
            return Optional.empty();
        }

        boolean javaUsername = JAVA_USERNAME_PATTERN.matcher(normalized).matches();
        boolean bedrockUsername = isBedrockUsername(normalized, bedrockUsernamePrefix);
        if (!javaUsername && !bedrockUsername) {
            return Optional.empty();
        }

        return Optional.of(normalized.toLowerCase(Locale.ROOT));
    }

    public static Optional<String> parseBedrockUsername(String input, String bedrockUsernamePrefix) {
        String normalized = trim(input);
        if (normalized == null || !isValidBedrockUsernamePrefix(bedrockUsernamePrefix)) {
            return Optional.empty();
        }

        String usernameBody = normalized;
        if (!bedrockUsernamePrefix.isEmpty() && normalized.startsWith(bedrockUsernamePrefix)) {
            usernameBody = normalized.substring(bedrockUsernamePrefix.length());
        }
        if (!BEDROCK_USERNAME_BODY_PATTERN.matcher(usernameBody).matches()) {
            return Optional.empty();
        }
        return Optional.of((bedrockUsernamePrefix + usernameBody).toLowerCase(Locale.ROOT));
    }

    public static boolean isValidBedrockUsernamePrefix(String prefix) {
        if (prefix == null || prefix.length() > 8) {
            return false;
        }
        return prefix.chars().allMatch(character -> !Character.isWhitespace(character)
                && !Character.isISOControl(character));
    }

    public static Optional<String> parseDiscordId(String input) {
        String normalized = trim(input);
        if (normalized == null || !DISCORD_ID_PATTERN.matcher(normalized).matches()) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    public static Optional<ParsedTarget> parseTarget(String input) {
        return parseTarget(input, DEFAULT_BEDROCK_USERNAME_PREFIX);
    }

    public static Optional<ParsedTarget> parseTarget(String input, String bedrockUsernamePrefix) {
        Optional<String> discordId = parseDiscordId(input);
        if (discordId.isPresent()) {
            return Optional.of(new ParsedTarget(TargetType.DISCORD_ID, discordId.get()));
        }

        return parseMinecraftUsername(input, bedrockUsernamePrefix)
                .map(username -> new ParsedTarget(TargetType.MINECRAFT_USERNAME, username));
    }

    private static boolean isBedrockUsername(String username, String prefix) {
        if (!isValidBedrockUsernamePrefix(prefix)) {
            return false;
        }
        if (!prefix.isEmpty() && !username.startsWith(prefix)) {
            return false;
        }

        String usernameBody = username.substring(prefix.length());
        return BEDROCK_USERNAME_BODY_PATTERN.matcher(usernameBody).matches();
    }

    private static String trim(String input) {
        return input == null ? null : input.trim();
    }
}
