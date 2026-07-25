package net.justempire.discordverificator.commands;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.utils.AccountIdentifierUtil;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.Locale;

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

        String action = args[0].trim().toLowerCase(Locale.ROOT);
        if (!action.equals("allow") && !action.equals("block") && !action.equals("unblock")) {
            sender.sendMessage(MessageColorizer.colorize(
                    DiscordVerificatorPlugin.getMessage("in-game.invalid-decision-format")
            ));
            return true;
        }

        var parsedTarget = AccountIdentifierUtil.parseTarget(args[1]);
        if (parsedTarget.isEmpty()) {
            sender.sendMessage(MessageColorizer.colorize(
                    DiscordVerificatorPlugin.getMessage("in-game.invalid-decision-target-format")
            ));
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                AccountIdentifierUtil.ParsedTarget target = parsedTarget.get();
                String discordId = switch (target.type()) {
                    case DISCORD_ID -> target.value();
                    case MINECRAFT_USERNAME ->
                            userManager.getDiscordIdByMinecraftUsername(target.value());
                };

                boolean updated;
                updated = switch (action) {
                    case "allow" -> userManager.setAllowSharedIp(discordId, true);
                    case "block" -> userManager.setUserBlocked(discordId, true);
                    case "unblock" -> userManager.setUserBlocked(discordId, false);
                    default -> throw new IllegalStateException("Validated action became invalid");
                };

                if (updated) {
                    plugin.sendMessageOnMainThread(sender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.action-success")));
                } else {
                    plugin.sendMessageOnMainThread(sender, MessageColorizer.colorize(
                            DiscordVerificatorPlugin.getMessage("in-game.decision-target-not-found")
                    ));
                }
            } catch (UserNotFoundException e) {
                plugin.sendMessageOnMainThread(sender, MessageColorizer.colorize(
                        DiscordVerificatorPlugin.getMessage("in-game.decision-target-not-found")
                ));
            } catch (Exception e) {
                plugin.sendMessageOnMainThread(sender, MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage("in-game.action-failed")));
                plugin.getLogger().log(Level.SEVERE, "Failed to execute decision command", e);
            }
        });

        return true;
    }
}
