package net.justempire.discordverificator.commands;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

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

        if (args.length != 2) return false;

        String action = args[0];
        String discordId = args[1];

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (action.equalsIgnoreCase("allow")) {
                    userManager.setAllowSharedIp(discordId, true);
                    sender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("action-success")));
                } else if (action.equalsIgnoreCase("block")) {
                    userManager.setUserBlocked(discordId, true);
                    sender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("action-success")));
                }
            } catch (Exception e) {
                sender.sendMessage(MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("action-failed")));
                e.printStackTrace();
            }
        });

        return true;
    }
}