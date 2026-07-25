package net.justempire.discordverificator.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseServiceTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseService databaseService;

    @BeforeEach
    void setUp() throws SQLException {
        databaseService = new DatabaseService(
                temporaryDirectory.toString(),
                Logger.getLogger(DatabaseServiceTest.class.getName())
        );
        databaseService.initialize();
    }

    @AfterEach
    void tearDown() {
        databaseService.closeConnection();
    }

    @Test
    void configuresConnectionForIntegrityAndConcurrentAccess() throws SQLException {
        assertEquals(1, readIntegerPragma("foreign_keys"));
        assertTrue(readIntegerPragma("busy_timeout") >= 5_000);
        assertEquals("wal", readStringPragma("journal_mode"));
    }

    @Test
    void rollsBackFailedTransaction() throws SQLException {
        assertThrows(SQLException.class, () -> databaseService.executeInTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users (discord_id, current_allowed_ip) VALUES ('123', '127.0.0.1')");
            }
            throw new SQLException("Force rollback");
        }));

        try (Statement statement = databaseService.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM users WHERE discord_id = '123'")) {
            assertTrue(resultSet.next());
            assertEquals(0, resultSet.getInt(1));
        }
    }

    @Test
    void migratesManualAccessColumnForExistingUsersTable() throws Exception {
        Path legacyDirectory = temporaryDirectory.resolve("legacy");
        Files.createDirectories(legacyDirectory);
        String legacyUrl = "jdbc:sqlite:" + legacyDirectory.resolve("database.db");

        try (Connection connection = DriverManager.getConnection(legacyUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                        discord_id TEXT PRIMARY KEY,
                        current_allowed_ip TEXT,
                        is_blocked INTEGER DEFAULT 0,
                        allow_shared_ip INTEGER DEFAULT 0
                    )
                    """);
            statement.execute("""
                    INSERT INTO users (discord_id, current_allowed_ip)
                    VALUES ('123456789012345678', '192.0.2.5')
                    """);
        }

        DatabaseService legacyService = new DatabaseService(
                legacyDirectory.toString(),
                Logger.getLogger(DatabaseServiceTest.class.getName() + ".legacy")
        );
        try {
            legacyService.initialize();
            try (Statement statement = legacyService.getConnection().createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT manual_access_bypass FROM users WHERE discord_id = '123456789012345678'"
                 )) {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt("manual_access_bypass"));
            }
        } finally {
            legacyService.closeConnection();
        }
    }

    @Test
    void migrationsAreIdempotent() throws SQLException {
        databaseService.initialize();

        try (Statement statement = databaseService.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(users)")) {
            int manualAccessColumns = 0;
            while (resultSet.next()) {
                if ("manual_access_bypass".equals(resultSet.getString("name"))) {
                    manualAccessColumns++;
                }
            }
            assertEquals(1, manualAccessColumns);
        }
    }

    @Test
    void incompatibleLegacySchemaFailsInitialization() throws Exception {
        Path brokenDirectory = temporaryDirectory.resolve("broken");
        Files.createDirectories(brokenDirectory);
        String brokenUrl = "jdbc:sqlite:" + brokenDirectory.resolve("database.db");

        try (Connection connection = DriverManager.getConnection(brokenUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (discord_id TEXT PRIMARY KEY)");
        }

        DatabaseService brokenService = new DatabaseService(
                brokenDirectory.toString(),
                Logger.getLogger(DatabaseServiceTest.class.getName() + ".broken")
        );
        try {
            assertThrows(SQLException.class, brokenService::initialize);
        } finally {
            brokenService.closeConnection();
        }
    }

    private int readIntegerPragma(String pragma) throws SQLException {
        try (Statement statement = databaseService.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA " + pragma)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private String readStringPragma(String pragma) throws SQLException {
        try (Statement statement = databaseService.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA " + pragma)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
