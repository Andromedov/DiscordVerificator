package net.justempire.discordverificator.services;

import java.io.File;
import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection", "SqlDialectInspection"})
public class DatabaseService {
    @FunctionalInterface
    interface TransactionWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private static final int BUSY_TIMEOUT_MS = 5_000;

    private final String url;
    private final Logger logger;
    private Connection connection;

    public DatabaseService(String dataFolder, Logger logger) {
        this.logger = logger;
        // SQLite file location
        this.url = "jdbc:sqlite:" + dataFolder + File.separator + "database.db";

        try {
            // Load driver explicitly to ensure it's available
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) { this.logger.log(Level.SEVERE, "SQLite JDBC Driver not found!", e); }
    }

    public void initialize() throws SQLException {
        getConnection(); // Ensure connection is established
        createTables();
        performMigrations();
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(url);
            configureConnection(connection);
        }
        return connection;
    }

    private void configureConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
    }

    synchronized <T> T executeInTransaction(TransactionWork<T> work) throws SQLException {
        Connection activeConnection = getConnection();
        boolean previousAutoCommit = activeConnection.getAutoCommit();
        activeConnection.setAutoCommit(false);

        try {
            T result = work.execute(activeConnection);
            activeConnection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try {
                activeConnection.rollback();
            } catch (SQLException rollbackException) {
                e.addSuppressed(rollbackException);
            }
            throw e;
        } finally {
            activeConnection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) { logger.log(Level.SEVERE, "Failed to close database connection", e); }
    }

    private void createTables() throws SQLException {
        String createUsersTable = "CREATE TABLE IF NOT EXISTS users (" +
                "discord_id TEXT PRIMARY KEY, " +
                "current_allowed_ip TEXT, " +
                "is_blocked INTEGER DEFAULT 0, " +
                "allow_shared_ip INTEGER DEFAULT 0, " +
                "manual_access_bypass INTEGER DEFAULT 0" +
                ");";

        String createLinksTable = "CREATE TABLE IF NOT EXISTS linked_accounts (" +
                "minecraft_username TEXT PRIMARY KEY, " +
                "discord_id TEXT NOT NULL, " +
                "last_login TIMESTAMP, " +
                "linked_at TIMESTAMP, " +
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

        // Table to track all historical IPs verified by the user (for multi-account detection)
        String createUserIpsTable = "CREATE TABLE IF NOT EXISTS user_ips (" +
                "discord_id TEXT, " +
                "ip_address TEXT, " +
                "last_seen TIMESTAMP, " +
                "PRIMARY KEY (discord_id, ip_address), " +
                "FOREIGN KEY(discord_id) REFERENCES users(discord_id) ON DELETE CASCADE" +
                ");";

        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute(createUsersTable);
            stmt.execute(createLinksTable);
            stmt.execute(createHistoryTable);
            stmt.execute(createUserIpsTable);
        }
    }

    private void performMigrations() throws SQLException {
        executeInTransaction(activeConnection -> {
            addColumnIfMissing(
                    activeConnection,
                    "users",
                    "is_blocked",
                    "ALTER TABLE users ADD COLUMN is_blocked INTEGER DEFAULT 0"
            );
            addColumnIfMissing(
                    activeConnection,
                    "users",
                    "allow_shared_ip",
                    "ALTER TABLE users ADD COLUMN allow_shared_ip INTEGER DEFAULT 0"
            );
            addColumnIfMissing(
                    activeConnection,
                    "users",
                    "manual_access_bypass",
                    "ALTER TABLE users ADD COLUMN manual_access_bypass INTEGER DEFAULT 0"
            );
            addColumnIfMissing(
                    activeConnection,
                    "linked_accounts",
                    "linked_at",
                    "ALTER TABLE linked_accounts ADD COLUMN linked_at TIMESTAMP"
            );

            try (Statement statement = activeConnection.createStatement()) {
                statement.executeUpdate("""
                        INSERT OR IGNORE INTO user_ips (discord_id, ip_address, last_seen)
                        SELECT discord_id, current_allowed_ip, CURRENT_TIMESTAMP
                        FROM users
                        WHERE current_allowed_ip IS NOT NULL AND current_allowed_ip != ''
                        """);
            }
            return null;
        });
    }

    private void addColumnIfMissing(
            Connection activeConnection,
            String tableName,
            String columnName,
            String alterSql
    ) throws SQLException {
        if (columnExists(activeConnection, tableName, columnName)) {
            return;
        }
        try (Statement statement = activeConnection.createStatement()) {
            statement.execute(alterSql);
        }
    }

    private boolean columnExists(Connection activeConnection, String tableName, String columnName)
            throws SQLException {
        try (Statement statement = activeConnection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
