package net.justempire.discordverificator.discord;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record DiscordModerationAction(Type type, String targetDiscordId) {
    private static final Pattern COMPONENT_ID_PATTERN =
            Pattern.compile("^dv_(allow|block)_(\\d{17,20})$");

    static Optional<DiscordModerationAction> parse(String componentId) {
        if (componentId == null) {
            return Optional.empty();
        }

        Matcher matcher = COMPONENT_ID_PATTERN.matcher(componentId);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Type type = switch (matcher.group(1)) {
            case "allow" -> Type.ALLOW;
            case "block" -> Type.BLOCK;
            default -> throw new IllegalStateException("Unexpected moderation action");
        };
        return Optional.of(new DiscordModerationAction(type, matcher.group(2)));
    }

    enum Type {
        ALLOW,
        BLOCK
    }
}
