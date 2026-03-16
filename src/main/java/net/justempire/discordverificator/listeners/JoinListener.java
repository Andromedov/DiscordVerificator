package net.justempire.discordverificator.listeners;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.exceptions.NoCodesFoundException;
import net.justempire.discordverificator.models.User;
import net.justempire.discordverificator.services.ConfirmationCodeService;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import net.justempire.discordverificator.utils.MessageColorizer;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JoinListener implements Listener {
    private final UserManager userManager;
    private final DiscordVerificatorPlugin plugin;
    private final ConfirmationCodeService confirmationCodeService;

    private final Map<String, Long> alertCooldowns = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 3 * 60 * 60 * 1000; // 3 hours

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

        try {
            discordId = userManager.getDiscordIdByMinecraftUsername(playerName);
            user = userManager.getFullUserByDiscordId(discordId);
        } catch (UserNotFoundException e) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("in-game.account-not-linked"));
            return;
        }

        if (user.isBlocked()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("in-game.account-blocked"));
            return;
        }

        if (!plugin.getDiscordBot().isBotEnabled()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("in-game.bot-not-working"));
            return;
        }

        // --- MULTI-ACCOUNT / SHARED IP DETECTION ---
        List<String> otherIds = userManager.getOtherDiscordIdsWithSameIp(ipAddress, discordId);

        if (!otherIds.isEmpty()) {
            boolean blockedAssociationFound = false;
            String blockedNeighborId = null;

            for (String neighborId : otherIds) {
                try {
                    User neighbor = userManager.getFullUserByDiscordId(neighborId);
                    if (neighbor.isBlocked()) {
                        blockedAssociationFound = true;
                        blockedNeighborId = neighborId;
                        break;
                    }
                } catch (UserNotFoundException ignored) {}
            }

            if (blockedAssociationFound) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - alertCooldowns.getOrDefault(playerName, 0L) > ALERT_COOLDOWN_MS) {
                    List<String> blockedUsernames = userManager.getMinecraftUsernamesByDiscordIds(List.of(blockedNeighborId));
                    String blockedName = blockedUsernames.isEmpty() ? blockedNeighborId : blockedUsernames.get(0);

                    sendAdminAlertBlockedAssociation(playerName, blockedName);
                    alertCooldowns.put(playerName, currentTime);
                }

                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("in-game.security-check"));
                return;
            }

            if (!user.isSharedIpAllowed()) {
                int globalLimit = plugin.getConfig().getInt("default-max-accounts-per-ip", 1);

                if ((otherIds.size() + 1) > globalLimit) {
                    long currentTime = System.currentTimeMillis();

                    if (currentTime - alertCooldowns.getOrDefault(playerName, 0L) > ALERT_COOLDOWN_MS) {
                        List<String> associatedUsernames = userManager.getMinecraftUsernamesByDiscordIds(otherIds);

                        sendAdminAlertMultiIp(playerName, ipAddress, associatedUsernames, discordId);
                        plugin.getDiscordBot().sendSecurityAlert(playerName, ipAddress, associatedUsernames, discordId);

                        alertCooldowns.put(playerName, currentTime);
                    }

                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("in-game.security-check"));
                    return;
                }
            }
        }

        // Enforces IP verification cooldown or generates confirmation code
        if (!ipAddress.equals(user.getCurrentAllowedIp())) {
            try {
                long secondsSinceLast = userManager.getSecondsSinceLastCode(discordId, ipAddress);
                if (secondsSinceLast < 30) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            String.format(getMessage("in-game.wait-until-verification"), 30 - secondsSinceLast));
                    return;
                }
            } catch (NoCodesFoundException ignored) {
            }

            String code = confirmationCodeService.generateVerificationCode(playerName, ipAddress);
            userManager.updateLastTimeUserReceivedCode(discordId, ipAddress);

            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    String.format(getMessage("in-game.confirm-with-command"), code));
        } else {
            userManager.updatePlayerLoginTime(playerName, ipAddress);
        }
    }

    private void sendAdminAlertMultiIp(String playerName, String ip, List<String> others, String currentDiscordId) {
        String neighbors = String.join(", ", others);
        String message = String.format(getMessage("admin-alerts.multi-ip"), playerName, ip, neighbors);

        TextComponent alert = new TextComponent(MessageColorizer.colorize(message + "\n"));

        TextComponent btnAllow = new TextComponent(MessageColorizer.colorize(getMessage("admin-alerts.button-allow") + " "));
        btnAllow.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dvdecision allow " + currentDiscordId));
        btnAllow.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(MessageColorizer.colorize(getMessage("admin-alerts.hover-allow")))));

        TextComponent btnBlock = new TextComponent(MessageColorizer.colorize(getMessage("admin-alerts.button-block")));
        btnBlock.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dvdecision block " + currentDiscordId));
        btnBlock.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(MessageColorizer.colorize(getMessage("admin-alerts.hover-block")))));

        alert.addExtra(btnAllow);
        alert.addExtra(btnBlock);

        broadcastToAdmins(alert);
    }

    private void sendAdminAlertBlockedAssociation(String playerName, String blockedNeighborName) {
        String message = String.format(getMessage("admin-alerts.blocked-assoc"), playerName, blockedNeighborName);
        TextComponent alert = new TextComponent(MessageColorizer.colorize(message + "\n"));
        broadcastToAdmins(alert);
    }

    private void broadcastToAdmins(TextComponent message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("discordVerificator.alerts")) {
                p.spigot().sendMessage(message);
            }
        }
    }

    private String getMessage(String key) {
        return DiscordVerificatorPlugin.getMessage(key);
    }
}