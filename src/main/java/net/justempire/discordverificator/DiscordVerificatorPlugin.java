package net.justempire.discordverificator;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.justempire.discordverificator.commands.*;
import net.justempire.discordverificator.discord.DiscordBot;
import net.justempire.discordverificator.listeners.JoinListener;
import net.justempire.discordverificator.services.ConfirmationCodeService;
import net.justempire.discordverificator.services.DatabaseService;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiscordVerificatorPlugin extends JavaPlugin {
    public record RuntimeSettings(
            String requiredGuildId,
            String discordInviteLink,
            int defaultMaxAccountsPerIp,
            boolean requireIpVerification,
            String discordAlertChannelId,
            String discordAdminRoleId,
            long verificationCodeExpirationSeconds,
            boolean maskIpAddressesInStaffMessages,
            int ipHistoryRetentionDays
    ) {
    }

    public enum DiscordServiceState {
        STOPPED,
        STARTING,
        READY,
        FAILED,
        STOPPING
    }

    private Logger logger;
    private UserManager userManager;
    private ConfirmationCodeService confirmationCodeService;
    private BukkitTask verificationCodeCleanupTask;
    private BukkitTask ipDataCleanupTask;
    private volatile DiscordBot discordBot;

    private volatile JDA currentJDA;
    private volatile DiscordServiceState discordServiceState = DiscordServiceState.STOPPED;
    private final AtomicLong discordLifecycleGeneration = new AtomicLong();
    private volatile RuntimeSettings runtimeSettings;
    private static volatile Map<String, String> messages = Map.of();

    private volatile boolean isReloading = false;
    private volatile boolean shuttingDown = false;

    @Override
    public void onEnable() {
        logger = this.getLogger();
        shuttingDown = false;

        mergeConfig();
        refreshRuntimeSettings();

        DatabaseService databaseService = new DatabaseService(getDataFolder().getAbsolutePath(), logger);
        try {
            databaseService.initialize();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not initialize database! Disabling plugin.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize UserManager with Database and JSON path for migration
        String jsonPath = String.format("%s/users.json", getDataFolder());
        userManager = new UserManager(databaseService, jsonPath, logger);

        confirmationCodeService = new ConfirmationCodeService(runtimeSettings.verificationCodeExpirationSeconds());
        verificationCodeCleanupTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                confirmationCodeService::purgeExpiredCodes,
                20L * 60,
                20L * 60
        );
        ipDataCleanupTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                this::purgeExpiredIpData,
                1L,
                20L * 60 * 60 * 24
        );

        // Setting up the messages
        setupMessages();

        // Setting up the bot
        setupBot();

        getServer().getPluginManager().registerEvents(new JoinListener(this, userManager, confirmationCodeService), this);

        // Commands
        Objects.requireNonNull(getCommand("link")).setExecutor(new LinkCommand(this, userManager));
        Objects.requireNonNull(getCommand("unlink")).setExecutor(new UnlinkCommand(this, userManager));
        Objects.requireNonNull(getCommand("relink")).setExecutor(new RelinkCommand(this, userManager));
        Objects.requireNonNull(getCommand("dvreload")).setExecutor(new ReloadCommand(this));
        Objects.requireNonNull(getCommand("dvinfo")).setExecutor(new InfoCommand(this, userManager));
        Objects.requireNonNull(getCommand("dvdecision")).setExecutor(new DecisionCommand(this, userManager));

        logger.info("Enabled successfully!");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;

        if (verificationCodeCleanupTask != null) {
            verificationCodeCleanupTask.cancel();
            verificationCodeCleanupTask = null;
        }
        if (confirmationCodeService != null) {
            confirmationCodeService.clear();
        }
        if (ipDataCleanupTask != null) {
            ipDataCleanupTask.cancel();
            ipDataCleanupTask = null;
        }

        if (userManager != null) userManager.onShutDown(); // Closes DB connection

        shutdownBotSync();
        logger.info("Shutting down!");
    }

    private void shutdownBotSync() {
        long generation = discordLifecycleGeneration.incrementAndGet();
        discordServiceState = DiscordServiceState.STOPPING;

        JDA jdaToShutdown = currentJDA;
        DiscordBot botToShutdown = discordBot;
        currentJDA = null;
        discordBot = null;

        if (jdaToShutdown != null) {
            if (botToShutdown != null) {
                jdaToShutdown.removeEventListener(botToShutdown);
            }
            jdaToShutdown.shutdown();
            try {
                if (!jdaToShutdown.awaitShutdown(5, TimeUnit.SECONDS)) {
                    logger.warning("JDA took too long to shutdown, forcing...");
                    jdaToShutdown.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warning("Interrupted while waiting for JDA to shutdown!");
                jdaToShutdown.shutdownNow();
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
        }

        if (discordLifecycleGeneration.get() == generation) {
            discordServiceState = DiscordServiceState.STOPPED;
        }
    }

    public DiscordBot getDiscordBot() {
        return discordBot;
    }

    public DiscordBot getReadyDiscordBot() {
        DiscordBot bot = discordBot;
        if (discordServiceState != DiscordServiceState.READY || bot == null || !bot.isBotEnabled()) {
            return null;
        }
        return bot;
    }

    public DiscordServiceState getDiscordServiceState() {
        return discordServiceState;
    }

    public JDA getJDA() {
        return currentJDA;
    }

    public RuntimeSettings getRuntimeSettings() {
        return runtimeSettings;
    }

    public void runOnMainThread(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (!shuttingDown) {
            getServer().getScheduler().runTask(this, action);
        }
    }

    public void sendMessageOnMainThread(CommandSender sender, String message) {
        runOnMainThread(() -> sender.sendMessage(message));
    }

    private void mergeConfig() {
        saveDefaultConfig();
        File configFile = new File(getDataFolder(), "config.yml");
        mergeYamlFile(configFile, getConfig(), "config.yml", true);
    }

    private void mergeYamlFile(File targetFile, FileConfiguration targetConfig, String resourcePath, boolean deepKeySearch) {
        InputStream defaultStream = getResource(resourcePath);
        if (defaultStream == null) {
            return;
        }

        YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
        boolean hasChanges = false;

        for (String key : defaultConfig.getKeys(deepKeySearch)) {
            if (!targetConfig.contains(key)) {
                targetConfig.set(key, defaultConfig.get(key));
                hasChanges = true;
            }
        }

        if (hasChanges) {
            try {
                targetConfig.save(targetFile);
                logger.info("Updated " + targetFile.getName() + " with missing default values.");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Could not save merged file " + targetFile.getName(), e);
            }
        }
    }

    private void setupBot() {
        String token = getConfig().getString("token");
        long generation = discordLifecycleGeneration.incrementAndGet();

        if (token == null || token.isBlank() || token.contains("DISCORD_BOT_TOKEN")) {
            discordServiceState = DiscordServiceState.FAILED;
            logger.warning("Please set a valid bot token in config.yml!");
            return;
        }

        discordServiceState = DiscordServiceState.STARTING;

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            DiscordBot bot = new DiscordBot(this, logger, userManager, confirmationCodeService);
            JDA candidateJDA = null;

            try {
                candidateJDA = JDABuilder.createLight(token)
                        .addEventListeners(bot)
                        .setAutoReconnect(true)
                        .setStatus(OnlineStatus.ONLINE)
                        .build();

                candidateJDA.awaitReady();

                if (shuttingDown || discordLifecycleGeneration.get() != generation) {
                    candidateJDA.shutdownNow();
                    return;
                }

                this.currentJDA = candidateJDA;
                this.discordBot = bot;
                this.discordServiceState = DiscordServiceState.READY;
                logger.info("Discord Bot connected and ready!");
            } catch (Exception e) {
                if (candidateJDA != null) {
                    candidateJDA.shutdownNow();
                }
                if (discordLifecycleGeneration.get() == generation) {
                    this.discordServiceState = DiscordServiceState.FAILED;
                }
                logger.log(Level.SEVERE, "Failed to connect to Discord! Check your token or internet connection.", e);
            }
        });
    }

    public void reload() {
        if (isReloading) return;
        isReloading = true;

        logger.info("Reloading plugin...");

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            shutdownBotSync();

            try {
                getServer().getScheduler().runTask(this, () -> {
                    try {
                        reloadConfig();
                        mergeConfig();
                        refreshRuntimeSettings();
                        setupMessages();
                        confirmationCodeService.updateExpirationSeconds(runtimeSettings.verificationCodeExpirationSeconds());
                        setupBot();
                        logger.info("Reload complete!");
                    } finally {
                        isReloading = false;
                    }
                });
            } catch (RuntimeException e) {
                isReloading = false;
                logger.log(Level.SEVERE, "Reload failed!", e);
            }
        });
    }

    private void setupMessages() {
        Map<String, String> loadedMessages = new HashMap<>();

        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        File fallbackFile = new File(langFolder, "en.yml");
        if (!fallbackFile.exists()) {
            try {
                saveResource("lang/en.yml", false);
            } catch (IllegalArgumentException ignored) {}
        }

        String lang = getConfig().getString("language", "en");
        File langFile = new File(langFolder, lang + ".yml");

        if (!langFile.exists()) {
            try {
                saveResource("lang/" + lang + ".yml", false);
            } catch (IllegalArgumentException e) {
                logger.warning("Language file '" + lang + ".yml' not found in plugin JAR or folder! Falling back to en.yml");
                langFile = fallbackFile;
                lang = "en";
            }
        }

        YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);

        String defaultResourcePath = "lang/" + lang + ".yml";
        if (getResource(defaultResourcePath) == null) {
            defaultResourcePath = "lang/en.yml";
        }

        mergeYamlFile(langFile, langConfig, defaultResourcePath, true);

        for (String key : langConfig.getKeys(true)) {
            if (langConfig.isString(key)) {
                loadedMessages.put(key, langConfig.getString(key));
            }
        }
        messages = Map.copyOf(loadedMessages);
    }

    private void refreshRuntimeSettings() {
        runtimeSettings = new RuntimeSettings(
                getConfig().getString("required-guild-id", ""),
                getConfig().getString("discord-invite-link", "https://discord.gg/"),
                Math.max(1, getConfig().getInt("default-max-accounts-per-ip", 1)),
                getConfig().getBoolean("require-ip-verification", true),
                getConfig().getString("discord-alerts.channel-id", ""),
                getConfig().getString("discord-alerts.admin-role-id", ""),
                getConfig().getLong("verification-code.expiration-seconds", 300),
                getConfig().getBoolean("privacy.mask-ip-addresses-in-staff-messages", true),
                Math.max(1, Math.min(3650, getConfig().getInt("privacy.ip-history-retention-days", 30)))
        );
    }

    private void purgeExpiredIpData() {
        if (shuttingDown || userManager == null) {
            return;
        }

        try {
            UserManager.IpDataCleanupResult result =
                    userManager.purgeExpiredIpData(runtimeSettings.ipHistoryRetentionDays());
            if (result.orphanUsersDeleted() > 0
                    || result.historicalIpsDeleted() > 0
                    || result.verificationRecordsDeleted() > 0) {
                logger.info("Expired IP data cleanup removed "
                        + result.orphanUsersDeleted() + " orphan user record(s), "
                        + result.historicalIpsDeleted() + " historical IP record(s), and "
                        + result.verificationRecordsDeleted() + " verification record(s).");
            }
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Failed to clean up expired IP data", e);
        }
    }

    public static String getMessage(String key) {
        if (messages == null) return String.format("Message %s wasn't found (messages list is null)", key);
        if (messages.get(key) == null) return String.format("Message %s wasn't found", key);

        return MessageColorizer.colorize(messages.get(key));
    }
}
