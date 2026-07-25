package net.justempire.discordverificator.commands;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.exceptions.MinecraftUsernameAlreadyLinkedException;
import net.justempire.discordverificator.utils.AccountIdentifierUtil;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

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

        if (arguments.length != 2) {
            commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.invalid-link-format")));
            return true;
        }

        var parsedPlayerName = AccountIdentifierUtil.parseMinecraftUsername(arguments[0]);
        if (parsedPlayerName.isEmpty()) {
            commandSender.sendMessage(MessageColorizer.colorize(
                    DiscordVerificatorPlugin.getMessage("in-game.invalid-minecraft-username-format")
            ));
            return true;
        }

        var parsedDiscordId = AccountIdentifierUtil.parseDiscordId(arguments[1]);
        if (parsedDiscordId.isEmpty()) {
            commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.invalid-user-id-format")));
            return true;
        }

        String playerName = parsedPlayerName.get();
        String discordUserId = parsedDiscordId.get();

        // Run database operation asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                userManager.linkUser(discordUserId, playerName);
                plugin.sendMessageOnMainThread(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.successfully-linked")));
            } catch (MinecraftUsernameAlreadyLinkedException e) {
                plugin.sendMessageOnMainThread(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.player-already-linked")));
            } catch (Exception e) {
                plugin.sendMessageOnMainThread(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("discord.error-occurred")));
                plugin.getLogger().log(Level.SEVERE, "Failed to execute link command", e);
            }
        });

        return true;
    }
}
