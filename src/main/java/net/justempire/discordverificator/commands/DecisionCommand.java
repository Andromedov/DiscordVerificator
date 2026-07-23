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

import java.util.logging.Level;

public class DecisionCommand implements CommandExecutor {
    private final UserManager userManager;
    private final DiscordVerificatorPlugin plugin;

    public DecisionCommand(DiscordVerificatorPlugin plugin, UserManager userManager) {
        this.plugin = plugin;
        this.userManager = userManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("discordVerificator.alerts")) {
            sender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.not-enough-permissions")));
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.invalid-decision-format")));
            return true;
        }

        String action = args[0];
        String target = args[1];

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String discordId = target;

                if (target.matches("^[a-zA-Z0-9_]{3,16}$")) {
                    try {
                        discordId = userManager.getDiscordIdByMinecraftUsername(target);
                    } catch (UserNotFoundException ignored) { }
                }

                boolean updated;
                if (action.equalsIgnoreCase("allow")) {
                    updated = userManager.setAllowSharedIp(discordId, true);
                } else if (action.equalsIgnoreCase("block")) {
                    updated = userManager.setUserBlocked(discordId, true);
                } else if (action.equalsIgnoreCase("unblock")) {
                    updated = userManager.setUserBlocked(discordId, false);
                } else {
                    plugin.sendMessageOnMainThread(sender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.invalid-decision-format")));
                    return;
                }

                if (updated) {
                    plugin.sendMessageOnMainThread(sender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.action-success")));
                } else {
                    plugin.sendMessageOnMainThread(sender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.action-failed")));
                }
            } catch (Exception e) {
                plugin.sendMessageOnMainThread(sender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.action-failed")));
                plugin.getLogger().log(Level.SEVERE, "Failed to execute decision command", e);
            }
        });

        return true;
    }
}
