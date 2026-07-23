package net.justempire.discordverificator.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
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
    public enum GuildMembershipStatus {
        MEMBER,
        NOT_MEMBER,
        TEMPORARY_ERROR,
        CONFIGURATION_ERROR
    }

    private final DiscordVerificatorPlugin plugin;
    private final Logger logger;
    private final UserManager userManager;
    private final ConfirmationCodeService confirmationCodeService;

    private volatile boolean botEnabled = false;

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
        SlashCommandData commandData = Commands.slash("confirm", getMessage("discord.confirm-command"));
        commandData.addOption(OptionType.STRING, "code", getMessage("discord.verification-code-you-got"));

        event.getJDA().updateCommands().addCommands(commandData).complete();

        botEnabled = true;
        logger.info("Bot started!");
    }

    public boolean isBotEnabled() { return botEnabled; }

    /**
     * Checks whether the user is on the specified Discord server.
     * This method makes an API request to Discord, so it is blocking (use only in asynchronous events).
     * @param discordId The Discord ID of the user to check
     * @param guildId The ID of the Discord server to check against
     * @return a status that distinguishes membership, absence, temporary failures, and configuration errors
     */
    public GuildMembershipStatus checkUserInGuild(String discordId, String guildId) {
        JDA jda = plugin.getJDA();
        if (jda == null) {
            logger.warning("Cannot check Discord guild membership because JDA is unavailable.");
            return GuildMembershipStatus.TEMPORARY_ERROR;
        }

        Guild guild;
        try {
            guild = jda.getGuildById(guildId);
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, "Invalid Discord guild ID configured: " + guildId, e);
            return GuildMembershipStatus.CONFIGURATION_ERROR;
        }

        if (guild == null) {
            logger.warning("Unable to find the Discord server (Guild) with ID " + guildId + ". Is the bot on the server?");
            return GuildMembershipStatus.CONFIGURATION_ERROR;
        }

        try {
            if (guild.getMemberById(discordId) != null) return GuildMembershipStatus.MEMBER;

            Member member = guild.retrieveMemberById(discordId).complete();
            return member != null ? GuildMembershipStatus.MEMBER : GuildMembershipStatus.NOT_MEMBER;
        } catch (net.dv8tion.jda.api.exceptions.ErrorResponseException e) {
            if (e.getErrorResponse() == net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MEMBER ||
                    e.getErrorResponse() == net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_USER) {
                return GuildMembershipStatus.NOT_MEMBER;
            }
            logger.log(Level.WARNING, "API error when checking if a player is on the Discord server", e);
            return GuildMembershipStatus.TEMPORARY_ERROR;
        } catch (Exception e) {
            logger.log(Level.WARNING, "An unexpected error occurred while checking if a player is on the Discord server", e);
            return GuildMembershipStatus.TEMPORARY_ERROR;
        }
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        // If the command is "confirm"
        if (event.getName().equals("confirm")) onConfirmSlashCommand(event);
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("dv_")) return;

        String adminRoleId = plugin.getConfig().getString("discord-alerts.admin-role-id");
        if (adminRoleId != null && !adminRoleId.isEmpty()) {
            if (event.getMember() == null || event.getMember().getRoles().stream().noneMatch(r -> r.getId().equals(adminRoleId))) {
                event.reply(getMessage("discord.no-permission")).setEphemeral(true).queue();
                return;
            }
        }

        event.deferEdit().queue();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                MessageEmbed oldEmbed = event.getMessage().getEmbeds().get(0);
                EmbedBuilder newEmbed = new EmbedBuilder(oldEmbed);

                if (id.startsWith("dv_allow_")) {
                    String targetDiscordId = id.substring("dv_allow_".length());
                    userManager.setAllowSharedIp(targetDiscordId, true);

                    // Update the Embed message design
                    newEmbed.setColor(Color.GREEN);
                    newEmbed.addField(
                            getMessage("discord.decision-approved-title"),
                            String.format(getMessage("discord.decision-approved-desc"), event.getUser().getAsMention()),
                            false
                    );

                } else if (id.startsWith("dv_block_")) {
                    String targetDiscordId = id.substring("dv_block_".length());
                    userManager.setUserBlocked(targetDiscordId, true);

                    // Update the Embed message design
                    newEmbed.setColor(Color.RED);
                    newEmbed.addField(
                            getMessage("discord.decision-blocked-title"),
                            String.format(getMessage("discord.decision-blocked-desc"), event.getUser().getAsMention()),
                            false
                    );
                }

                // Set the updated Embed and an empty component list
                event.getHook().editOriginalEmbeds(newEmbed.build()).setComponents().queue();

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error handling button interaction", e);
                event.getHook().sendMessage(getMessage("discord.error-saving")).setEphemeral(true).queue();
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
        embed.setTitle(getMessage("discord.alert-title"));
        embed.setColor(Color.ORANGE);

        String description = String.format(getMessage("discord.alert-desc-player"), playerName) + "\n" +
                String.format(getMessage("discord.alert-desc-ip"), ip) + "\n" +
                String.format(getMessage("discord.alert-desc-discord"), targetDiscordId) + "\n\n" +
                String.format(getMessage("discord.alert-desc-associated"), String.join(", ", associatedUsernames));

        embed.setDescription(description);

        // Sends security alert embed with interactive trust/block buttons
        channel.sendMessageEmbeds(embed.build())
                .setComponents(ActionRow.of(
                        Button.success("dv_allow_" + targetDiscordId, getMessage("discord.button-trust")),
                        Button.danger("dv_block_" + targetDiscordId, getMessage("discord.button-block"))
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
                    MessageEmbed embed = generateEmbed(getMessage("discord.invalid-usage"), getMessage("discord.provide-code-please"), 0xF63B2D);
                    event.getHook().sendMessageEmbeds(embed).queue(); // Use hook instead of reply
                    return;
                }

                // Trying to get code data
                UsernameAndIp codeData;
                try {
                    codeData = confirmationCodeService.getDataByCodeAndRemove(code.getAsString(), discordId);
                } catch (InvalidCodeException e) {
                    MessageEmbed embed = generateEmbed(getMessage("discord.invalid-code"), getMessage("discord.invalid-code-description"), 0xF63B2D);
                    event.getHook().sendMessageEmbeds(embed).queue();
                    return;
                }

                try {
                    String linkedDiscordId = userManager.getDiscordIdByMinecraftUsername(codeData.getUsername());

                    if (!linkedDiscordId.equals(discordId)) {
                        MessageEmbed embed = generateEmbed(getMessage("discord.error-occurred"), getMessage("discord.its-not-your-account"), 0xF63B2D);
                        event.getHook().sendMessageEmbeds(embed).queue();
                        return;
                    }

                    // Confirming the code
                    confirmIp(discordId, codeData.getIpAddress());
                    MessageEmbed embed = generateEmbed(
                            getMessage("discord.allowed"),
                            String.format(getMessage("discord.allowed-to-join-from-ip"), codeData.getIpAddress()),
                            0x9ACD32);

                    event.getHook().sendMessageEmbeds(embed).queue();
                } catch (UserNotFoundException e) {
                    // Send user the message if he was not found
                    MessageEmbed embed = generateEmbed(getMessage("discord.user-not-found"), getMessage("discord.user-not-found-description"), 0xF63B2D);
                    event.getHook().sendMessageEmbeds(embed).queue();
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "An internal error occurred during confirm command", e);
                event.getHook().sendMessage(getMessage("discord.internal-error")).queue();
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
