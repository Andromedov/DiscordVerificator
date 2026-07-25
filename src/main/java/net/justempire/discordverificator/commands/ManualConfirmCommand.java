package net.justempire.discordverificator.commands;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import net.justempire.discordverificator.services.PendingLoginAttemptService;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.utils.AccountIdentifierUtil;
import net.justempire.discordverificator.utils.IpAddressUtil;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;

public class ManualConfirmCommand implements CommandExecutor {
    private final DiscordVerificatorPlugin plugin;
    private final UserManager userManager;
    private final PendingLoginAttemptService pendingLoginAttempts;

    public ManualConfirmCommand(
            DiscordVerificatorPlugin plugin,
            UserManager userManager,
            PendingLoginAttemptService pendingLoginAttempts
    ) {
        this.plugin = plugin;
        this.userManager = userManager;
        this.pendingLoginAttempts = pendingLoginAttempts;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(message("in-game.manual-confirm-console-only"));
            return true;
        }

        if (args.length > 0 && args[0].toLowerCase(Locale.ROOT).equals("revoke")) {
            if (args.length != 2) {
                sender.sendMessage(message("in-game.manual-confirm-usage"));
                return true;
            }

            var parsedMinecraftUsername = AccountIdentifierUtil.parseMinecraftUsername(args[1]);
            if (parsedMinecraftUsername.isEmpty()) {
                sender.sendMessage(message("in-game.invalid-minecraft-username-format"));
                return true;
            }
            revoke(sender, parsedMinecraftUsername.get());
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(message("in-game.manual-confirm-usage"));
            return true;
        }

        var parsedMinecraftUsername = AccountIdentifierUtil.parseMinecraftUsername(args[0]);
        if (parsedMinecraftUsername.isEmpty()) {
            sender.sendMessage(message("in-game.invalid-minecraft-username-format"));
            return true;
        }
        String minecraftUsername = parsedMinecraftUsername.get();
        Optional<PendingLoginAttemptService.PendingLoginAttempt> claimedAttempt =
                pendingLoginAttempts.claimRecentAttempt(minecraftUsername);
        if (claimedAttempt.isEmpty()) {
            sender.sendMessage(message("in-game.manual-confirm-no-attempt"));
            return true;
        }

        PendingLoginAttemptService.PendingLoginAttempt attempt = claimedAttempt.get();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                userManager.enableManualAccess(attempt.minecraftUsername(), attempt.ipAddress());
                String displayedIp = IpAddressUtil.displayForStaff(
                        attempt.ipAddress(),
                        plugin.getRuntimeSettings().maskIpAddressesInStaffMessages()
                );
                plugin.getLogger().info("Console enabled manual Discord bypass for Minecraft user "
                        + attempt.minecraftUsername() + " at IP " + displayedIp);
                plugin.sendMessageOnMainThread(
                        sender,
                        String.format(
                                message("in-game.manual-confirm-success"),
                                attempt.minecraftUsername(),
                                displayedIp
                        )
                );
            } catch (UserNotFoundException e) {
                plugin.sendMessageOnMainThread(sender, message("in-game.player-was-not-linked"));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to enable manual Discord bypass", e);
                plugin.sendMessageOnMainThread(sender, message("in-game.action-failed"));
            }
        });
        return true;
    }

    private void revoke(CommandSender sender, String minecraftUsername) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!userManager.revokeManualAccess(minecraftUsername)) {
                    plugin.sendMessageOnMainThread(sender, message("in-game.player-was-not-linked"));
                    return;
                }

                plugin.getLogger().info("Console revoked manual Discord bypass for Minecraft user "
                        + minecraftUsername);
                plugin.sendMessageOnMainThread(
                        sender,
                        String.format(message("in-game.manual-confirm-revoked"), minecraftUsername)
                );
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to revoke manual Discord bypass", e);
                plugin.sendMessageOnMainThread(sender, message("in-game.action-failed"));
            }
        });
    }

    private String message(String key) {
        return MessageColorizer.colorize(DiscordVerificatorPlugin.getMessage(key));
    }
}
