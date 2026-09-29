package net.justempire.discordverificator.commands;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.exceptions.MinecraftUsernameAlreadyLinkedException;
import net.justempire.discordverificator.utils.AccountIdentifierUtil;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.List;

public class LinkCommand implements CommandExecutor {
    private final UserManager userManager;
    private final DiscordVerificatorPlugin plugin;

    public LinkCommand(DiscordVerificatorPlugin plugin, UserManager userManager) {
        this.plugin = plugin;
        this.userManager = userManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] arguments) {
        if (!commandSender.hasPermission("discordVerificator.link")) {
            commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.not-enough-permissions")));
            return true;
        }

        if (arguments.length != 2 && arguments.length != 3) {
            commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.invalid-link-format")));
            return true;
        }

        boolean pairedLink = arguments.length == 3;
        var parsedJavaName = pairedLink
                ? AccountIdentifierUtil.parseJavaUsername(arguments[0])
                : plugin.parseMinecraftUsername(arguments[0]);
        var parsedBedrockName = pairedLink
                ? plugin.parseBedrockUsername(arguments[1])
                : java.util.Optional.<String>empty();
        if (parsedJavaName.isEmpty() || (pairedLink && parsedBedrockName.isEmpty())) {
            commandSender.sendMessage(MessageColorizer.colorize(
                    DiscordVerificatorPlugin.getMessage("in-game.invalid-minecraft-username-format")
            ));
            return true;
        }

        var parsedDiscordId = AccountIdentifierUtil.parseDiscordId(arguments[pairedLink ? 2 : 1]);
        if (parsedDiscordId.isEmpty()) {
            commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.invalid-user-id-format")));
            return true;
        }

        List<String> playerNames = pairedLink
                ? List.of(parsedJavaName.get(), parsedBedrockName.get()).stream().distinct().toList()
                : List.of(parsedJavaName.get());
        String discordUserId = parsedDiscordId.get();

        // Run database operation asynchronously
        plugin.runAsync(() -> {
            try {
                userManager.linkUsers(discordUserId, playerNames);
                plugin.sendMessageScheduled(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.successfully-linked")));
            } catch (MinecraftUsernameAlreadyLinkedException e) {
                plugin.sendMessageScheduled(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.player-already-linked")));
            } catch (Exception e) {
                plugin.sendMessageScheduled(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("discord.error-occurred")));
                plugin.getLogger().log(Level.SEVERE, "Failed to execute link command", e);
            }
        });

        return true;
    }
}
