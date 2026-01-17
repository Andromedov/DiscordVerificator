package net.justempire.discordverificator.services;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public class DatabaseService {
    private final String url;
    private final Logger logger;
    private Connection connection;

    public DatabaseService(String dataFolder, Logger logger) {
        this.logger = logger;
        // SQLite file location
        this.url = "jdbc:sqlite:" + dataFolder + File.separator + "database.db";

        try {
            // Load driver explicitely to ensure it's available
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.severe("SQLite JDBC Driver not found!");
            e.printStackTrace();
        }
    }

    public void initialize() throws SQLException {
        getConnection(); // Ensure connection is established
        createTables();
        performMigrations();
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(url);
        }
        return connection;
    }

    public void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        String createUsersTable = "CREATE TABLE IF NOT EXISTS users (" +
                "discord_id TEXT PRIMARY KEY, " +
                "current_allowed_ip TEXT, " +
                "is_blocked INTEGER DEFAULT 0, " +
                "allow_shared_ip INTEGER DEFAULT 0" +
                ");";

        String createLinksTable = "CREATE TABLE IF NOT EXISTS linked_accounts (" +
                "minecraft_username TEXT PRIMARY KEY, " +
                "discord_id TEXT NOT NULL, " +
                "last_login TIMESTAMP, " +
                "FOREIGN KEY(discord_id) REFERENCES users(discord_id) ON DELETE CASCADE" +
                ");";

        // Table to track when a user received a code from a specific IP (to avoid spam)
        String createHistoryTable = "CREATE TABLE IF NOT EXISTS verification_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "discord_id TEXT NOT NULL, " +
                "ip_address TEXT NOT NULL, " +
                "last_received TIMESTAMP NOT NULL, " +
                "FOREIGN KEY(discord_id) REFERENCES users(discord_id) ON DELETE CASCADE" +
                ");";

        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute(createUsersTable);
            stmt.execute(createLinksTable);
            stmt.execute(createHistoryTable);
        }
    }

    // Simple migration to add columns if they don't exist in existing DBs
    private void performMigrations() {
        try (Statement stmt = getConnection().createStatement()) {
            try {
                stmt.execute("ALTER TABLE users ADD COLUMN is_blocked INTEGER DEFAULT 0;");
                logger.info("Migrated database: Added is_blocked column.");
            } catch (SQLException ignored) {}

            try {
                stmt.execute("ALTER TABLE users ADD COLUMN allow_shared_ip INTEGER DEFAULT 0;");
                logger.info("Migrated database: Added allow_shared_ip column.");
            } catch (SQLException ignored) {}
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}