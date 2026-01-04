package net.justempire.discordverificator;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
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

import javax.security.auth.login.LoginException;
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

        // Setting up the bot
        setupBot();

        // Setting up the messages
        setupMessages();

        // Setting up listeners
        getServer().getPluginManager().registerEvents(new JoinListener(this, userManager, confirmationCodeService), this);

        // Setting up commands
        LinkCommand linkCommand = new LinkCommand(this, userManager);
        getCommand("link").setExecutor(linkCommand);

        UnlinkCommand unlinkCommand = new UnlinkCommand(this, userManager);
        getCommand("unlink").setExecutor(unlinkCommand);

        ReloadCommand reloadCommand = new ReloadCommand(this);
        getCommand("dvreload").setExecutor(reloadCommand);

        logger.info("Enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (userManager != null) userManager.onShutDown(); // Closes DB connection
        if (currentJDA != null) currentJDA.shutdown();
        logger.info("Shutting down!");
    }

    public DiscordBot getDiscordBot() {
        return discordBot;
    }

    private void setupBot() {
        String token = getConfig().getString("token");
        DiscordBot bot = new DiscordBot(logger, userManager, confirmationCodeService);

        try {
            this.currentJDA = JDABuilder.createLight(token)
                    .addEventListeners(bot)
                    .setAutoReconnect(true)
                    // .setChunkingFilter(ChunkingFilter.ALL)
                    .setStatus(OnlineStatus.ONLINE)
                    .build();
        } catch (Exception e) { e.printStackTrace(); }

        this.discordBot = bot;
    }

    public void reload() {
        try { currentJDA.shutdownNow(); }
        catch (Exception ignored) { }

        // Reloading the config
        reloadConfig();

        // Reloading the messages from config
        setupMessages();

        // userManager.reload();

        // Starting the bot
        setupBot();
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