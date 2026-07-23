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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void moderationUpdatesReportWhetherTargetExists() throws Exception {
        String discordId = "666666666666666666";

        assertFalse(userManager.setUserBlocked(discordId, true));
        assertFalse(userManager.setAllowSharedIp(discordId, true));

        userManager.linkUser(discordId, "ModeratedPlayer");

        assertTrue(userManager.setUserBlocked(discordId, true));
        assertTrue(userManager.setAllowSharedIp(discordId, true));
        assertTrue(userManager.getFullUserByDiscordId(discordId).isBlocked());
        assertTrue(userManager.getFullUserByDiscordId(discordId).isSharedIpAllowed());
    }

    @Test
    void expiredIpHistoryIsDeletedButCurrentAllowedIpIsRetained() throws Exception {
        String currentUserId = "777777777777777777";
        String historicalUserId = "888888888888888888";
        String currentAllowedIp = "198.51.100.10";
        String historicalIp = "203.0.113.7";

        userManager.linkUser(currentUserId, "CurrentIpPlayer");
        userManager.updateIp(currentUserId, currentAllowedIp);
        userManager.linkUser(historicalUserId, "HistoricalIpPlayer");
        userManager.updatePlayerLoginTime("HistoricalIpPlayer", historicalIp);
        userManager.updateLastTimeUserReceivedCode(historicalUserId, historicalIp);

        try (Statement statement = databaseService.getConnection().createStatement()) {
            statement.executeUpdate("UPDATE user_ips SET last_seen = datetime('now', '-60 days')");
            statement.executeUpdate("UPDATE verification_history SET last_received = datetime('now', '-60 days')");
        }

        UserManager.IpDataCleanupResult result = userManager.purgeExpiredIpData(30);

        assertEquals(0, result.orphanUsersDeleted());
        assertEquals(1, result.historicalIpsDeleted());
        assertEquals(1, result.verificationRecordsDeleted());
        assertEquals(1, countRows("user_ips", "ip_address", currentAllowedIp));
        assertEquals(0, countRows("user_ips", "ip_address", historicalIp));
        assertEquals(0, countRows("verification_history", "ip_address", historicalIp));
    }

    @Test
    void ipCleanupRejectsUnsafeRetentionPeriod() {
        assertThrows(IllegalArgumentException.class, () -> userManager.purgeExpiredIpData(0));
    }

    @Test
    void unlinkingLastAccountDeletesOrphanUserAndIpData() throws Exception {
        String discordId = "999999999999999999";
        String ipAddress = "192.0.2.25";
        userManager.linkUser(discordId, "FirstLinkedPlayer");
        userManager.linkUser(discordId, "SecondLinkedPlayer");
        userManager.updateIp(discordId, ipAddress);
        userManager.updateLastTimeUserReceivedCode(discordId, ipAddress);

        userManager.unlinkUser("FirstLinkedPlayer");
        assertEquals(discordId, userManager.getFullUserByDiscordId(discordId).getDiscordId());

        userManager.unlinkUser("SecondLinkedPlayer");

        assertThrows(UserNotFoundException.class, () -> userManager.getFullUserByDiscordId(discordId));
        assertEquals(0, countRows("users", "discord_id", discordId));
        assertEquals(0, countRows("user_ips", "discord_id", discordId));
        assertEquals(0, countRows("verification_history", "discord_id", discordId));
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
