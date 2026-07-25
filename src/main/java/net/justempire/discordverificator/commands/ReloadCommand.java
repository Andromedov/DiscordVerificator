package net.justempire.discordverificator.commands;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class ReloadCommand implements CommandExecutor {
    private final DiscordVerificatorPlugin plugin;

    public ReloadCommand(DiscordVerificatorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] arguments) {
        if (!commandSender.hasPermission("discordVerificator.reload")) {
            commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.not-enough-permissions")));
            return true;
        }

        if (arguments.length != 0) {
            commandSender.sendMessage(MessageColorizer.colorize(
                    DiscordVerificatorPlugin.getMessage("in-game.invalid-reload-format")
            ));
            return true;
        }

        if (plugin.reload()) {
            commandSender.sendMessage(MessageColorizer.colorize(
                    DiscordVerificatorPlugin.getMessage("in-game.reload-started")
            ));
        } else {
            commandSender.sendMessage(MessageColorizer.colorize(
                    DiscordVerificatorPlugin.getMessage("in-game.reload-in-progress")
            ));
        }

        return true;
    }
}
