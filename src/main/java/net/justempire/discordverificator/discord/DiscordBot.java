package net.justempire.discordverificator.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.justempire.discordverificator.DiscordVerificatorPlugin;
import net.justempire.discordverificator.exceptions.InvalidCodeException;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import net.justempire.discordverificator.models.UsernameAndIp;
import net.justempire.discordverificator.services.ConfirmationCodeService;
import net.justempire.discordverificator.services.UserManager;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiscordBot extends ListenerAdapter {
    private final DiscordVerificatorPlugin plugin;
    private final Logger logger;
    private final UserManager userManager;
    private final ConfirmationCodeService confirmationCodeService;

    private boolean botEnabled = false;

    public DiscordBot(DiscordVerificatorPlugin plugin, Logger logger, UserManager repository, ConfirmationCodeService confirmationCodeService) {
        this.plugin = plugin;
        this.logger = logger;
        this.userManager = repository;
        this.confirmationCodeService = confirmationCodeService;
    }

    @Override
    public void onShutdown(@NotNull ShutdownEvent event) {
        logger.info("Shutting down the bot!");
        botEnabled = false;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        SlashCommandData commandData = Commands.slash("confirm", getMessage("confirm-command"));
        commandData.addOption(OptionType.STRING, "code", getMessage("verification-code-you-got"));

        event.getJDA().updateCommands().addCommands(commandData).complete();

        botEnabled = true;
        logger.info("Bot started!");
    }

    public boolean isBotEnabled() { return botEnabled; }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        // If command is "confirm"
        if (event.getName().equals("confirm")) onConfirmSlashCommand(event);
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("dv_")) return;

        String adminRoleId = plugin.getConfig().getString("discord-alerts.admin-role-id");
        if (adminRoleId != null && !adminRoleId.isEmpty()) {
            if (event.getMember() == null || event.getMember().getRoles().stream().noneMatch(r -> r.getId().equals(adminRoleId))) {
                event.reply("❌ You do not have permission to use this!").setEphemeral(true).queue();
                return;
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (id.startsWith("dv_allow_")) {
                String targetDiscordId = id.substring("dv_allow_".length());
                userManager.setAllowSharedIp(targetDiscordId, true); // Довіряємо акаунту
                event.reply("✅ Successfully marked user (Discord ID: " + targetDiscordId + ") as Trusted Bypass.").queue();
                event.getMessage().editMessageComponents().queue();
            } else if (id.startsWith("dv_block_")) {
                String targetDiscordId = id.substring("dv_block_".length());
                userManager.setUserBlocked(targetDiscordId, true);
                event.reply("🛑 Successfully blocked user (Discord ID: " + targetDiscordId + ").").queue();
                event.getMessage().editMessageComponents().queue();
            }
        });
    }

    /**
     * Sends multi-account security alert with interactive response buttons
     */
    public void sendSecurityAlert(String playerName, String ip, List<String> associatedUsernames, String targetDiscordId) {
        if (plugin.getJDA() == null) return;

        String channelId = plugin.getConfig().getString("discord-alerts.channel-id");
        if (channelId == null || channelId.isEmpty()) return;

        TextChannel channel = plugin.getJDA().getTextChannelById(channelId);
        if (channel == null) {
            logger.warning("Could not find Discord channel for alerts! Check your config.");
            return;
        }

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("⚠️ Security Alert: Multi-Account Detected");
        embed.setColor(Color.ORANGE);
        embed.setDescription("**Player Attempting to Join:** `" + playerName + "`\n" +
                "**IP Address:** `" + ip + "`\n" +
                "**Discord ID:** `" + targetDiscordId + "`\n\n" +
                "**Associated Minecraft Accounts (same IP):**\n" + String.join(", ", associatedUsernames));

        // Sends security alert embed with interactive trust/block buttons
        channel.sendMessageEmbeds(embed.build())
                .setComponents(ActionRow.of(
                        Button.success("dv_allow_" + targetDiscordId, "Trust User (Bypass)"),
                        Button.danger("dv_block_" + targetDiscordId, "Block User")
                )).queue();
    }

    private void onConfirmSlashCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Getting ID of sender
                String discordId = event.getUser().getId();

                // Getting the code from command arguments (options)
                OptionMapping code = event.getOption("code");

                // If code wasn't provided
                if (code == null) {
                    MessageEmbed embed = generateEmbed(getMessage("invalid-usage"), getMessage("provide-code-please"), 0xF63B2D);
                    event.getHook().sendMessageEmbeds(embed).queue(); // Use hook instead of reply
                    return;
                }

                // Trying to get code data
                UsernameAndIp codeData;
                try {
                    codeData = confirmationCodeService.getDataByCodeAndRemove(code.getAsString());
                } catch (InvalidCodeException e) {
                    MessageEmbed embed = generateEmbed(getMessage("invalid-code"), getMessage("invalid-code-description"), 0xF63B2D);
                    event.getHook().sendMessageEmbeds(embed).queue();
                    return;
                }

                try {
                    String linkedDiscordId = userManager.getDiscordIdByMinecraftUsername(codeData.getUsername());

                    if (!linkedDiscordId.equals(discordId)) {
                        MessageEmbed embed = generateEmbed(getMessage("error-occurred"), getMessage("its-not-your-account"), 0xF63B2D);
                        event.getHook().sendMessageEmbeds(embed).queue();
                        return;
                    }

                    // Confirming the code
                    confirmIp(discordId, codeData.getIpAddress());
                    MessageEmbed embed = generateEmbed(
                            getMessage("allowed"),
                            String.format(getMessage("allowed-to-join-from-ip"), codeData.getIpAddress()),
                            0x9ACD32);

                    event.getHook().sendMessageEmbeds(embed).queue();
                } catch (UserNotFoundException e) {
                    // Send user the message if he was not found
                    MessageEmbed embed = generateEmbed(getMessage("user-not-found"), getMessage("user-not-found-description"), 0xF63B2D);
                    event.getHook().sendMessageEmbeds(embed).queue();
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "An internal error occurred during confirm command", e);
                event.getHook().sendMessage("An internal error occurred.").queue();
            }
        });
    }

    private MessageEmbed generateEmbed(String title, String description, int color) {
        EmbedBuilder builder = new EmbedBuilder();
        builder.setTitle(title);
        builder.setDescription(description);
        builder.setColor(color);

        return builder.build();
    }

    private void confirmIp(String discordId, String ip) throws UserNotFoundException { userManager.updateIp(discordId, ip); }

    private String getMessage(String key) {
        return DiscordVerificatorPlugin.getMessage(key);
    }
}