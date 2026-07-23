package net.justempire.discordverificator.commands;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.exceptions.MinecraftUsernameAlreadyLinkedException;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class RelinkCommand implements CommandExecutor {
    private final UserManager userManager;
    private final DiscordVerificatorPlugin plugin;

    public RelinkCommand(DiscordVerificatorPlugin plugin, UserManager userManager) {
        this.plugin = plugin;
        this.userManager = userManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] arguments) {
        if (!commandSender.hasPermission("discordVerificator.relink")) {
            commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.not-enough-permissions")));
            return true;
        }

        if (arguments.length != 2) {
            commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.invalid-relink-format")));
            return true;
        }

        String oldPlayerName = arguments[0];
        String newPlayerName = arguments[1];

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                userManager.relinkUser(oldPlayerName, newPlayerName);
                plugin.sendMessageOnMainThread(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.successfully-relinked")));
            } catch (UserNotFoundException e) {
                plugin.sendMessageOnMainThread(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.player-was-not-linked")));
            } catch (MinecraftUsernameAlreadyLinkedException e) {
                plugin.sendMessageOnMainThread(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.player-already-linked")));
            } catch (Exception e) {
                plugin.sendMessageOnMainThread(commandSender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("discord.error-occurred")));
                e.printStackTrace();
            }
        });

        return true;
    }
}
