package net.justempire.discordverificator;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.justempire.discordverificator.commands.InfoCommand;
import net.justempire.discordverificator.commands.LinkCommand;
import net.justempire.discordverificator.commands.ReloadCommand;
import net.justempire.discordverificator.commands.UnlinkCommand;
import net.justempire.discordverificator.discord.DiscordBot;
import net.justempire.discordverificator.listeners.JoinListener;
import net.justempire.discordverificator.services.ConfirmationCodeService;
import net.justempire.discordverificator.services.DatabaseService;
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class DiscordVerificatorPlugin extends JavaPlugin {
    private Logger logger;
    private DatabaseService databaseService;
    private UserManager userManager;
    private ConfirmationCodeService confirmationCodeService;
    private DiscordBot discordBot;

    private JDA currentJDA;
    private static Map<String, String> messages = new HashMap<>();

    // Flag to prevent double reloading
    private boolean isReloading = false;

    @Override
    public void onEnable() {
        // Creating a config if it doesn't exist
        saveDefaultConfig();

        // Setting up the logger
        logger = this.getLogger();

        // Initialize Database Service
        databaseService = new DatabaseService(getDataFolder().getAbsolutePath(), logger);
        try {
            databaseService.initialize();
        } catch (SQLException e) {
            logger.severe("Could not initialize database! Disabling plugin.");
            e.printStackTrace();
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

        // Setting up listeners
        getServer().getPluginManager().registerEvents(new JoinListener(this, userManager, confirmationCodeService), this);

        // Setting up commands
        LinkCommand linkCommand = new LinkCommand(this, userManager);
        getCommand("link").setExecutor(linkCommand);

        UnlinkCommand unlinkCommand = new UnlinkCommand(this, userManager);
        getCommand("unlink").setExecutor(unlinkCommand);

        ReloadCommand reloadCommand = new ReloadCommand(this);
        getCommand("dvreload").setExecutor(reloadCommand);

        InfoCommand infoCommand = new InfoCommand(this, userManager);
        getCommand("dvinfo").setExecutor(infoCommand);

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

    private void setupBot() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String token = getConfig().getString("token");

            if (token == null || token.contains("DISCORD_BOT_TOKEN")) {
                logger.warning("Please set a valid bot token in config.yml!");
                return;
            }

            DiscordBot bot = new DiscordBot(this, logger, userManager, confirmationCodeService);

            try {
                // Ensure old instance is cleaned up if this is a retry
                if (this.currentJDA != null) {
                    this.currentJDA.shutdownNow();
                }

                this.currentJDA = JDABuilder.createLight(token)
                        .addEventListeners(bot)
                        .setAutoReconnect(true)
                        .setStatus(OnlineStatus.ONLINE)
                        .build();

                // Wait for the bot to be ready (safe because we are async)
                this.currentJDA.awaitReady();
                this.discordBot = bot;

                logger.info("Discord Bot connected and ready!");
            } catch (Exception e) {
                logger.severe("Failed to connect to Discord! Check your token or internet connection.");
                e.printStackTrace();
            }
        });
    }

    public void reload() {
        if (isReloading) return;
        isReloading = true;

        logger.info("Reloading plugin...");

        // Run reload logic asynchronously
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // 1. Shutdown existing bot completely
                if (currentJDA != null) {
                    currentJDA.shutdown();
                    if (!currentJDA.awaitShutdown(10, TimeUnit.SECONDS)) {
                        logger.warning("Forcing JDA shutdown during reload...");
                        currentJDA.shutdownNow();
                    }
                    currentJDA = null;
                }

                // 2. Reload config (Sync task required for Bukkit API safety)
                getServer().getScheduler().runTask(this, () -> {
                    reloadConfig();
                    setupMessages();

                    // 3. Start new bot (Async inside setupBot)
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

        // Getting the messages from the config
        ConfigurationSection configSection = getConfig().getConfigurationSection("messages");
        if (configSection != null) {
            // Adding these messages to dictionary
            Map<String, Object> messages = configSection.getValues(true);
            for (Map.Entry<String, Object> pair : messages.entrySet()) {
                DiscordVerificatorPlugin.messages.put(pair.getKey(), pair.getValue().toString());
            }
        }

        saveDefaultConfig();
    }

    // Returns a message from the config by key
    public static String getMessage(String key) {
        if (messages == null) return String.format("Message %s wasn't found (messages list is null)", key);
        if (messages.get(key) == null) return String.format("Message %s wasn't found", key);

        return MessageColorizer.colorize(messages.get(key));
    }
}