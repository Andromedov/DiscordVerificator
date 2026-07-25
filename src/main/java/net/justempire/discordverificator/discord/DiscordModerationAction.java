package net.justempire.discordverificator.discord;

import net.justempire.discordverificator.utils.AccountIdentifierUtil;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record DiscordModerationAction(Type type, String targetDiscordId) {
    private static final Pattern COMPONENT_ID_PATTERN =
            Pattern.compile("^dv_(allow|block)_(.+)$");

    static Optional<DiscordModerationAction> parse(String componentId) {
        if (componentId == null) {
            return Optional.empty();
        }

        Matcher matcher = COMPONENT_ID_PATTERN.matcher(componentId);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Optional<String> targetDiscordId = AccountIdentifierUtil.parseDiscordId(matcher.group(2));
        if (targetDiscordId.isEmpty()) {
            return Optional.empty();
        }

        Type type = switch (matcher.group(1)) {
            case "allow" -> Type.ALLOW;
            case "block" -> Type.BLOCK;
            default -> throw new IllegalStateException("Unexpected moderation action");
        };
        return Optional.of(new DiscordModerationAction(type, targetDiscordId.get()));
    }

    enum Type {
        ALLOW,
        BLOCK
    }
}
