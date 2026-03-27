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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiscordVerificatorPlugin extends JavaPlugin {
    private Logger logger;
    private UserManager userManager;
    private ConfirmationCodeService confirmationCodeService;
    private DiscordBot discordBot;

    private JDA currentJDA;
    private static Map<String, String> messages = new HashMap<>();

    private boolean isReloading = false;

    @Override
    public void onEnable() {
        logger = this.getLogger();

        mergeConfig();

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

        confirmationCodeService = new ConfirmationCodeService();

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
        if (userManager != null) userManager.onShutDown(); // Closes DB connection

        shutdownBotSync();
        logger.info("Shutting down!");
    }

    private void shutdownBotSync() {
        if (currentJDA != null) {
            currentJDA.removeEventListener(discordBot);
            currentJDA.shutdown();
            try {
                if (!currentJDA.awaitShutdown(5, TimeUnit.SECONDS)) {
                    logger.warning("JDA took too long to shutdown, forcing...");
                    currentJDA.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warning("Interrupted while waiting for JDA to shutdown!");
                currentJDA.shutdownNow();
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
            currentJDA = null;
        }
    }

    public DiscordBot getDiscordBot() {
        return discordBot;
    }

    public JDA getJDA() {
        return currentJDA;
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
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String token = getConfig().getString("token");

            if (token == null || token.contains("DISCORD_BOT_TOKEN")) {
                logger.warning("Please set a valid bot token in config.yml!");
                return;
            }

            DiscordBot bot = new DiscordBot(this, logger, userManager, confirmationCodeService);

            try {
                if (this.currentJDA != null) {
                    this.currentJDA.shutdownNow();
                }

                this.currentJDA = JDABuilder.createLight(token)
                        .addEventListeners(bot)
                        .setAutoReconnect(true)
                        .setStatus(OnlineStatus.ONLINE)
                        .build();

                this.currentJDA.awaitReady();
                this.discordBot = bot;

                logger.info("Discord Bot connected and ready!");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to connect to Discord! Check your token or internet connection.", e);
            }
        });
    }

    public void reload() {
        if (isReloading) return;
        isReloading = true;

        logger.info("Reloading plugin...");

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (currentJDA != null) {
                    currentJDA.shutdown();
                    if (!currentJDA.awaitShutdown(10, TimeUnit.SECONDS)) {
                        logger.warning("Forcing JDA shutdown during reload...");
                        currentJDA.shutdownNow();
                    }
                    currentJDA = null;
                }

                getServer().getScheduler().runTask(this, () -> {
                    reloadConfig();
                    mergeConfig();
                    setupMessages();

                    setupBot();

                    isReloading = false;
                    logger.info("Reload complete!");
                });

            } catch (InterruptedException e) {
                logger.severe("Reload interrupted!");
                isReloading = false;
                Thread.currentThread().interrupt();
            }
        });
    }

    private void setupMessages() {
        messages = new HashMap<>();

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
                messages.put(key, langConfig.getString(key));
            }
        }
    }

    public static String getMessage(String key) {
        if (messages == null) return String.format("Message %s wasn't found (messages list is null)", key);
        if (messages.get(key) == null) return String.format("Message %s wasn't found", key);

        return MessageColorizer.colorize(messages.get(key));
    }
}