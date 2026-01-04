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
import net.justempire.discordverificator.services.UserManager;
import net.justempire.discordverificator.utils.MessageColorizer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class DiscordVerificatorPlugin extends JavaPlugin {
    private Logger logger;
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

        // Setting up services
        userManager = new UserManager(String.format("%s/users.json", getDataFolder()));
        confirmationCodeService = new ConfirmationCodeService();

        // Setting up the bot
        setupBot();

        // Setting up the messages
        setupMessages();

        // Setting up listeners
        getServer().getPluginManager().registerEvents(new JoinListener(this, userManager, confirmationCodeService), this);

        // Setting up commands
        LinkCommand linkCommand = new LinkCommand(userManager);
        getCommand("link").setExecutor(linkCommand);

        UnlinkCommand unlinkCommand = new UnlinkCommand(userManager);
        getCommand("unlink").setExecutor(unlinkCommand);

        ReloadCommand reloadCommand = new ReloadCommand(this);
        getCommand("dvreload").setExecutor(reloadCommand);

        logger.info("Enabled successfully!");
    }

    @Override
    public void onDisable() {
        userManager.onShutDown();
        if (currentJDA != null) currentJDA.shutdown();
        logger.info("Shutting down!");
    }

    public DiscordBot getDiscordBot() {
        return discordBot;
    }

    // Setting up Discord bot
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
        // Trying to shut down the bot
        if (currentJDA != null) {
            currentJDA.shutdown();
            try {
                if (!currentJDA.awaitShutdown(5, TimeUnit.SECONDS)) {
                    currentJDA.shutdownNow();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                currentJDA.shutdownNow();
            }
        }

        // Reloading the config
        reloadConfig();

        // Reloading the messages from config
        setupMessages();

        // Reloading JSON file where users are stored
        userManager.reload();

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