package net.justempire.discordverificator.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserManagerConcurrencyTest {
    private static final int LINK_COUNT = 40;

    @TempDir
    Path temporaryDirectory;

    private DatabaseService databaseService;
    private UserManager userManager;

    @BeforeEach
    void setUp() throws SQLException {
        Logger logger = Logger.getLogger(UserManagerConcurrencyTest.class.getName());
        databaseService = new DatabaseService(temporaryDirectory.toString(), logger);
        databaseService.initialize();
        userManager = new UserManager(
                databaseService,
                temporaryDirectory.resolve("missing-users.json").toString(),
                logger
        );
    }

    @AfterEach
    void tearDown() {
        userManager.onShutDown();
    }

    @Test
    void serializesConcurrentWritesToSingleConnection() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < LINK_COUNT; index++) {
                int playerIndex = index;
                tasks.add(() -> {
                    userManager.linkUser("123456789012345678", "Player_" + playerIndex);
                    return null;
                });
            }

            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        try (Statement statement = databaseService.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM linked_accounts")) {
            resultSet.next();
            assertEquals(LINK_COUNT, resultSet.getInt(1));
        }
    }
}
