package net.justempire.discordverificator.listeners;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.exceptions.NoCodesFoundException;
import net.justempire.discordverificator.models.User;
import net.justempire.discordverificator.services.ConfirmationCodeService;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class JoinListener implements Listener {
    private final UserManager userManager;
    private final DiscordVerificatorPlugin plugin;
    private final ConfirmationCodeService confirmationCodeService;

    public JoinListener(DiscordVerificatorPlugin plugin, UserManager userManager, ConfirmationCodeService confirmationCodeService) {
        this.userManager = userManager;
        this.plugin = plugin;
        this.confirmationCodeService = confirmationCodeService;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        String playerName = event.getName();
        String ipAddress = event.getAddress().getHostAddress();

        User user;
        String discordId;

        // 1. Try to get Discord ID by Minecraft Username (Database Call)
        try {
            discordId = userManager.getDiscordIdByMinecraftUsername(playerName);
            // Reconstruct a lightweight user object or fetch full data
            user = userManager.getFullUserByDiscordId(discordId);
        } catch (UserNotFoundException e) {
            // Block join
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("account-not-linked"));
            return;
        }

        // 2. Check if bot is working
        if (!plugin.getDiscordBot().isBotEnabled()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("bot-not-working"));
            return;
        }

        // 3. IP Check
        if (!ipAddress.equals(user.getCurrentAllowedIp())) {

            // Check throttling (Spam prevention)
            try {
                long secondsSinceLast = userManager.getSecondsSinceLastCode(discordId, ipAddress);
                if (secondsSinceLast < 30) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            String.format(getMessage("wait-until-verification"), 30 - secondsSinceLast));
                    return;
                }
            } catch (NoCodesFoundException ignored) {
                // No previous codes, proceed
            }

            // Generate Code
            String code = confirmationCodeService.generateVerificationCode(playerName, ipAddress);

            // Log verification attempt (Database Call)
            userManager.updateLastTimeUserReceivedCode(discordId, ipAddress);

            // Kick with code
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    String.format(DiscordVerificatorPlugin.getMessage("confirm-with-command"), code));
        }
    }

    private String getMessage(String key) {
        return DiscordVerificatorPlugin.getMessage(key);
    }
}