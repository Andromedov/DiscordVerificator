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
            sender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("not-enough-permissions")));
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage(MessageColorizer.colorize("&cUsage: /dvdecision <allow|block> <Nick_or_DiscordID>"));
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

                if (action.equalsIgnoreCase("allow")) {
                    userManager.setAllowSharedIp(discordId, true);
                    sender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("action-success")));
                } else if (action.equalsIgnoreCase("block")) {
                    userManager.setUserBlocked(discordId, true);
                    sender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("action-success")));
                }
            } catch (Exception e) {
                sender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("action-failed")));
                plugin.getLogger().log(Level.SEVERE, "Failed to execute decision command", e);
            }
        });

        return true;
    }
}