package net.justempire.discordverificator.listeners;

import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.exceptions.NoCodesFoundException;
import net.justempire.discordverificator.models.User;
import net.justempire.discordverificator.services.ConfirmationCodeService;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import net.justempire.discordverificator.utils.MessageColorizer;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;

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

        try {
            discordId = userManager.getDiscordIdByMinecraftUsername(playerName);
            user = userManager.getFullUserByDiscordId(discordId);
        } catch (UserNotFoundException e) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("account-not-linked"));
            return;
        }

        if (user.isBlocked()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("account-blocked"));
            return;
        }

        if (!plugin.getDiscordBot().isBotEnabled()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("bot-not-working"));
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
                sendAdminAlertBlockedAssociation(playerName, blockedNeighborId);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("security-check"));
                return;
            }

            if (!user.isSharedIpAllowed()) {
                sendAdminAlertMultiIp(playerName, ipAddress, otherIds, discordId);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, getMessage("security-check"));
                return;
            }
        }


        if (!ipAddress.equals(user.getCurrentAllowedIp())) {
            try {
                long secondsSinceLast = userManager.getSecondsSinceLastCode(discordId, ipAddress);
                if (secondsSinceLast < 30) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            String.format(getMessage("wait-until-verification"), 30 - secondsSinceLast));
                    return;
                }
            } catch (NoCodesFoundException ignored) {
            }

            String code = confirmationCodeService.generateVerificationCode(playerName, ipAddress);
            userManager.updateLastTimeUserReceivedCode(discordId, ipAddress);

            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    String.format(DiscordVerificatorPlugin.getMessage("confirm-with-command"), code));
        } else { userManager.updatePlayerLoginTime(playerName); }
    }

    private void sendAdminAlertMultiIp(String playerName, String ip, List<String> others, String currentDiscordId) {
        String neighbors = String.join(", ", others);
        String message = String.format(getMessage("admin-alert-multi-ip"), playerName, ip, neighbors);

        TextComponent alert = new TextComponent(MessageColorizer.colorize(message + "\n"));

        TextComponent btnAllow = new TextComponent(MessageColorizer.colorize(getMessage("button-allow") + " "));
        btnAllow.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dvdecision allow " + currentDiscordId));
        btnAllow.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(MessageColorizer.colorize(getMessage("hover-allow"))).create()));

        TextComponent btnBlock = new TextComponent(MessageColorizer.colorize(getMessage("button-block")));
        btnBlock.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dvdecision block " + currentDiscordId));
        btnBlock.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(MessageColorizer.colorize(getMessage("hover-block"))).create()));

        alert.addExtra(btnAllow);
        alert.addExtra(btnBlock);

        broadcastToAdmins(alert);
    }

    private void sendAdminAlertBlockedAssociation(String playerName, String blockedNeighborId) {
        String message = String.format(getMessage("admin-alert-blocked-assoc"), playerName, blockedNeighborId);

        TextComponent alert = new TextComponent(MessageColorizer.colorize(message + "\n"));

        TextComponent btnBlock = new TextComponent(MessageColorizer.colorize(getMessage("button-block")));

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