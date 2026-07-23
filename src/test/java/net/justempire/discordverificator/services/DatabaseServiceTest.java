package net.justempire.discordverificator.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
