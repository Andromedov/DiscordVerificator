package net.justempire.discordverificator.commands;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class InfoCommand implements CommandExecutor {
    private final UserManager userManager;
    private final DiscordVerificatorPlugin plugin;

    public InfoCommand(DiscordVerificatorPlugin plugin, UserManager userManager) {
        this.plugin = plugin;
        this.userManager = userManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] arguments) {
        if (!commandSender.hasPermission("discordVerificator.info")) {
            commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("not-enough-permissions")));
            return true;
        }

        if (arguments.length != 1) {
            commandSender.sendMessage(MessageColorizer.colorize("&cUsage: /info <player>"));
            return true;
        }

        String targetPlayer = arguments[0];

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, String> info = userManager.getPlayerInfo(targetPlayer);

                commandSender.sendMessage(MessageColorizer.colorize("&8&m-----------------------------"));
                commandSender.sendMessage(MessageColorizer.colorize("&6&l Info for: &f" + targetPlayer));
                commandSender.sendMessage(MessageColorizer.colorize("&7 Discord ID: &f" + info.get("discord_id")));
                commandSender.sendMessage(MessageColorizer.colorize("&7 Allowed IP: &f" + info.get("current_ip")));
                commandSender.sendMessage(MessageColorizer.colorize("&7 Last Login: &f" + info.get("last_login")));
                commandSender.sendMessage(MessageColorizer.colorize("&8&m-----------------------------"));

            } catch (UserNotFoundException e) {
                commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("player-was-not-linked")));
            } catch (Exception e) {
                commandSender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("error-occurred")));
                e.printStackTrace();
            }
        });

        return true;
    }
}