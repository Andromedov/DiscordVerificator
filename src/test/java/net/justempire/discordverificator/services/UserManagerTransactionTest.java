package net.justempire.discordverificator.services;

import net.justempire.discordverificator.exceptions.MinecraftUsernameAlreadyLinkedException;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserManagerTransactionTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseService databaseService;
    private UserManager userManager;

    @BeforeEach
    void setUp() throws SQLException {
        Logger logger = Logger.getLogger(UserManagerTransactionTest.class.getName());
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
    void linkingAdditionalUsernameDoesNotClearVerifiedIp() throws Exception {
        String discordId = "111111111111111111";
        userManager.linkUser(discordId, "FirstPlayer");
        userManager.updateIp(discordId, "127.0.0.1");

        userManager.linkUser(discordId, "SecondPlayer");

        assertEquals("127.0.0.1", userManager.getFullUserByDiscordId(discordId).getCurrentAllowedIp());
    }

    @Test
    void failedLinkRollsBackNewUserRecord() throws SQLException {
        try (Statement statement = databaseService.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_test_link
                    BEFORE INSERT ON linked_accounts
                    WHEN NEW.minecraft_username = 'RejectedPlayer'
                    BEGIN
                        SELECT RAISE(ABORT, 'forced link failure');
                    END
                    """);
        }

        assertThrows(IllegalStateException.class,
                () -> userManager.linkUser("222222222222222222", "RejectedPlayer"));
        assertEquals(0, countRows("users", "discord_id", "222222222222222222"));
        assertEquals(0, countRows("linked_accounts", "minecraft_username", "RejectedPlayer"));
    }

    @Test
    void relinkPreservesMetadataAndChangesOnlyUsername() throws Exception {
        String discordId = "333333333333333333";
        userManager.linkUser(discordId, "OldPlayer");
        userManager.updatePlayerLoginTime("OldPlayer", "127.0.0.1");
        Map<String, String> before = userManager.getPlayerInfo("OldPlayer");

        userManager.relinkUser("OldPlayer", "NewPlayer");

        Map<String, String> after = userManager.getPlayerInfo("NewPlayer");
        assertEquals(discordId, after.get("discord_id"));
        assertEquals(before.get("linked_at"), after.get("linked_at"));
        assertEquals(before.get("last_login"), after.get("last_login"));
        assertThrows(UserNotFoundException.class, () -> userManager.getPlayerInfo("OldPlayer"));
    }

    @Test
    void rejectedRelinkLeavesOriginalLinkUntouched() throws Exception {
        userManager.linkUser("444444444444444444", "SourcePlayer");
        userManager.linkUser("555555555555555555", "TargetPlayer");

        assertThrows(MinecraftUsernameAlreadyLinkedException.class,
                () -> userManager.relinkUser("SourcePlayer", "targetplayer"));

        assertEquals("444444444444444444",
                userManager.getDiscordIdByMinecraftUsername("SourcePlayer"));
        assertEquals("555555555555555555",
                userManager.getDiscordIdByMinecraftUsername("TargetPlayer"));
    }

    private int countRows(String table, String column, String value) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?";
        try (PreparedStatement statement = databaseService.getConnection().prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
