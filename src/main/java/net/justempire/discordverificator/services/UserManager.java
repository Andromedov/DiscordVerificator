package net.justempire.discordverificator.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.justempire.discordverificator.exceptions.MinecraftUsernameAlreadyLinkedException;
import net.justempire.discordverificator.exceptions.NoCodesFoundException;
import net.justempire.discordverificator.exceptions.NotFoundException;
import net.justempire.discordverificator.exceptions.UserNotFoundException;
import net.justempire.discordverificator.models.User;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class UserManager {
    public record IpDataCleanupResult(
            int orphanUsersDeleted,
            int historicalIpsDeleted,
            int verificationRecordsDeleted
    ) {
    }

    private enum RelinkResult {
        SUCCESS,
        SOURCE_NOT_FOUND,
        TARGET_ALREADY_LINKED
    }

    private final DatabaseService databaseService;
    private final Logger logger;
    private final String jsonPath;

    public UserManager(DatabaseService databaseService, String jsonPath, Logger logger) {
        this.databaseService = databaseService;
        this.jsonPath = jsonPath;
        this.logger = logger;
        migrateFromJson();
    }

    private synchronized void migrateFromJson() {
        File jsonFile = new File(jsonPath);
        if (!jsonFile.exists()) return;

        ObjectMapper mapper = new ObjectMapper();
        try {
            List<User> oldUsers = mapper.readValue(jsonFile, new TypeReference<>() {});
            for (User oldUser : oldUsers) {
                try {
                    upsertUser(oldUser.getDiscordId(), oldUser.getCurrentAllowedIp());
                    for (String mcName : oldUser.linkedMinecraftUsernames) {
                        try { linkUser(oldUser.getDiscordId(), mcName); } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error migrating user data for: " + oldUser.getDiscordId(), e);
                }
            }
            boolean renamed = jsonFile.renameTo(new File(jsonPath + ".old"));
            if (!renamed) {
                logger.warning("Failed to rename users.json to users.json.old");
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to read users.json", e);
        }
    }

    private synchronized void upsertUser(String discordId, String currentIp) throws SQLException {
        String sql = "INSERT INTO users (discord_id, current_allowed_ip) VALUES(?, ?) " +
                "ON CONFLICT(discord_id) DO UPDATE SET current_allowed_ip = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, currentIp);
            pstmt.setString(3, currentIp);
            pstmt.executeUpdate();
        }
    }

    public synchronized String getDiscordIdByMinecraftUsername(String minecraftUsername) throws UserNotFoundException {
        String sql = "SELECT discord_id FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, minecraftUsername);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("discord_id");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error", e);
        }
        throw new UserNotFoundException();
    }

    public synchronized User getFullUserByDiscordId(String discordId) throws UserNotFoundException {
        String sql = "SELECT * FROM users WHERE discord_id = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                String id = rs.getString("discord_id");
                String ip = rs.getString("current_allowed_ip");
                boolean isBlocked = rs.getInt("is_blocked") == 1;
                boolean allowSharedIp = rs.getInt("allow_shared_ip") == 1;

                return new User(id, getLinkedAccounts(id), null, ip, isBlocked, allowSharedIp);
            }
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
        throw new UserNotFoundException();
    }

    // Check for other users with the same IP in the historical database!
    public synchronized List<String> getOtherDiscordIdsWithSameIp(String ip, String excludeDiscordId) {
        List<String> ids = new ArrayList<>();
        String sql = "SELECT DISTINCT discord_id FROM user_ips WHERE ip_address = ? AND discord_id != ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, ip);
            pstmt.setString(2, excludeDiscordId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) ids.add(rs.getString("discord_id"));
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
        return ids;
    }

    /**
     * Fetches usernames by Discord IDs with null/empty handling
     */
    public synchronized List<String> getMinecraftUsernamesByDiscordIds(List<String> discordIds) {
        List<String> usernames = new ArrayList<>();
        if (discordIds == null || discordIds.isEmpty()) return usernames;

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < discordIds.size(); i++) {
            placeholders.append("?");
            if (i < discordIds.size() - 1) placeholders.append(",");
        }

        String sql = "SELECT minecraft_username FROM linked_accounts WHERE discord_id IN (" + placeholders + ")";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            for (int i = 0; i < discordIds.size(); i++) {
                pstmt.setString(i + 1, discordIds.get(i));
            }
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) usernames.add(rs.getString("minecraft_username"));
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
        return usernames;
    }

    // Set Blocked Status
    public synchronized boolean setUserBlocked(String discordId, boolean blocked) {
        String sql = "UPDATE users SET is_blocked = ? WHERE discord_id = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, blocked ? 1 : 0);
            pstmt.setString(2, discordId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update blocked status for Discord ID " + discordId, e);
        }
    }

    // Set Allow Shared IP Status
    public synchronized boolean setAllowSharedIp(String discordId, boolean allowed) {
        String sql = "UPDATE users SET allow_shared_ip = ? WHERE discord_id = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, allowed ? 1 : 0);
            pstmt.setString(2, discordId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update shared IP status for Discord ID " + discordId, e);
        }
    }

    public synchronized Map<String, String> getPlayerInfo(String minecraftUsername) throws UserNotFoundException {
        String sql = "SELECT l.discord_id, l.last_login, l.linked_at, u.current_allowed_ip, u.is_blocked, u.allow_shared_ip " +
                "FROM linked_accounts l " +
                "JOIN users u ON l.discord_id = u.discord_id " +
                "WHERE l.minecraft_username = ? COLLATE NOCASE";

        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, minecraftUsername);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                Map<String, String> info = new HashMap<>();
                info.put("discord_id", rs.getString("discord_id"));
                info.put("current_ip", rs.getString("current_allowed_ip"));
                info.put("is_blocked", rs.getInt("is_blocked") == 1 ? "Yes" : "No");
                info.put("is_trusted_bypass", rs.getInt("allow_shared_ip") == 1 ? "Yes" : "No");

                Timestamp linkedAt = rs.getTimestamp("linked_at");
                info.put("linked_at", linkedAt != null ? linkedAt.toString() : "Unknown");

                Timestamp lastLogin = rs.getTimestamp("last_login");
                info.put("last_login", lastLogin != null ? lastLogin.toString() : "Never/Unknown");

                return info;
            }
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
        throw new UserNotFoundException();
    }

    public synchronized void updatePlayerLoginTime(String minecraftUsername, String ip) {
        try {
            String discordId = getDiscordIdByMinecraftUsername(minecraftUsername);
            databaseService.executeInTransaction(connection -> {
                Timestamp now = Timestamp.from(Instant.now());

                String updateLoginSql = "UPDATE linked_accounts SET last_login = ? WHERE minecraft_username = ? COLLATE NOCASE";
                try (PreparedStatement pstmt = connection.prepareStatement(updateLoginSql)) {
                    pstmt.setTimestamp(1, now);
                    pstmt.setString(2, minecraftUsername);
                    pstmt.executeUpdate();
                }

                String updateIpHistorySql = "INSERT INTO user_ips (discord_id, ip_address, last_seen) VALUES (?, ?, ?) " +
                        "ON CONFLICT(discord_id, ip_address) DO UPDATE SET last_seen = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(updateIpHistorySql)) {
                    pstmt.setString(1, discordId);
                    pstmt.setString(2, ip);
                    pstmt.setTimestamp(3, now);
                    pstmt.setTimestamp(4, now);
                    pstmt.executeUpdate();
                }
                return null;
            });
        } catch (UserNotFoundException | SQLException e) { logger.log(Level.SEVERE, "Database error or UserNotFound", e); }
    }

    private synchronized List<String> getLinkedAccounts(String discordId) {
        List<String> accounts = new ArrayList<>();
        String sql = "SELECT minecraft_username FROM linked_accounts WHERE discord_id = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) { accounts.add(rs.getString("minecraft_username")); }
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
        return accounts;
    }

    public synchronized void updateIp(String discordId, String newIp) throws UserNotFoundException {
        try {
            boolean userUpdated = databaseService.executeInTransaction(connection -> {
                String updateUserSql = "UPDATE users SET current_allowed_ip = ? WHERE discord_id = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(updateUserSql)) {
                    pstmt.setString(1, newIp);
                    pstmt.setString(2, discordId);
                    if (pstmt.executeUpdate() == 0) {
                        return false;
                    }
                }

                String updateIpHistorySql = "INSERT INTO user_ips (discord_id, ip_address, last_seen) VALUES (?, ?, ?) " +
                        "ON CONFLICT(discord_id, ip_address) DO UPDATE SET last_seen = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(updateIpHistorySql)) {
                    Timestamp now = Timestamp.from(Instant.now());
                    pstmt.setString(1, discordId);
                    pstmt.setString(2, newIp);
                    pstmt.setTimestamp(3, now);
                    pstmt.setTimestamp(4, now);
                    pstmt.executeUpdate();
                }
                return true;
            });

            if (!userUpdated) {
                throw new UserNotFoundException();
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error while updating verified IP", e);
        }
    }

    public synchronized void linkUser(String discordId, String minecraftUsername) throws MinecraftUsernameAlreadyLinkedException {
        try {
            boolean linked = databaseService.executeInTransaction(connection -> {
                String checkUsernameSql = "SELECT 1 FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
                try (PreparedStatement pstmt = connection.prepareStatement(checkUsernameSql)) {
                    pstmt.setString(1, minecraftUsername);
                    if (pstmt.executeQuery().next()) {
                        return false;
                    }
                }

                String createUserSql = "INSERT INTO users (discord_id, current_allowed_ip) VALUES (?, '') " +
                        "ON CONFLICT(discord_id) DO NOTHING";
                try (PreparedStatement pstmt = connection.prepareStatement(createUserSql)) {
                    pstmt.setString(1, discordId);
                    pstmt.executeUpdate();
                }

                String createLinkSql = "INSERT INTO linked_accounts (minecraft_username, discord_id, linked_at) VALUES (?, ?, ?)";
                try (PreparedStatement pstmt = connection.prepareStatement(createLinkSql)) {
                    pstmt.setString(1, minecraftUsername);
                    pstmt.setString(2, discordId);
                    pstmt.setTimestamp(3, Timestamp.from(Instant.now()));
                    pstmt.executeUpdate();
                }
                return true;
            });

            if (!linked) {
                throw new MinecraftUsernameAlreadyLinkedException();
            }
        } catch (SQLException e) {
            if (isConstraintViolation(e)) {
                throw new MinecraftUsernameAlreadyLinkedException();
            }
            throw new IllegalStateException("Failed to link Minecraft account", e);
        }
    }

    public synchronized void unlinkUser(String minecraftUsername) throws NotFoundException {
        try {
            boolean unlinked = databaseService.executeInTransaction(connection -> {
                String findDiscordId =
                        "SELECT discord_id FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
                String discordId;
                try (PreparedStatement statement = connection.prepareStatement(findDiscordId)) {
                    statement.setString(1, minecraftUsername);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return false;
                        }
                        discordId = resultSet.getString("discord_id");
                    }
                }

                String deleteLink =
                        "DELETE FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
                try (PreparedStatement statement = connection.prepareStatement(deleteLink)) {
                    statement.setString(1, minecraftUsername);
                    statement.executeUpdate();
                }

                String deleteOrphanUser = """
                        DELETE FROM users
                        WHERE discord_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM linked_accounts WHERE linked_accounts.discord_id = users.discord_id
                          )
                        """;
                try (PreparedStatement statement = connection.prepareStatement(deleteOrphanUser)) {
                    statement.setString(1, discordId);
                    statement.executeUpdate();
                }
                return true;
            });

            if (!unlinked) {
                throw new NotFoundException();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to unlink Minecraft account", e);
        }
    }

    public synchronized void relinkUser(String oldUsername, String newUsername) throws UserNotFoundException, MinecraftUsernameAlreadyLinkedException {
        try {
            RelinkResult result = databaseService.executeInTransaction(connection -> {
                String findSourceSql = "SELECT 1 FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
                try (PreparedStatement pstmt = connection.prepareStatement(findSourceSql)) {
                    pstmt.setString(1, oldUsername);
                    if (!pstmt.executeQuery().next()) {
                        return RelinkResult.SOURCE_NOT_FOUND;
                    }
                }

                if (!oldUsername.equalsIgnoreCase(newUsername)) {
                    String findTargetSql = "SELECT 1 FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
                    try (PreparedStatement pstmt = connection.prepareStatement(findTargetSql)) {
                        pstmt.setString(1, newUsername);
                        if (pstmt.executeQuery().next()) {
                            return RelinkResult.TARGET_ALREADY_LINKED;
                        }
                    }
                }

                String updateUsernameSql = "UPDATE linked_accounts SET minecraft_username = ? " +
                        "WHERE minecraft_username = ? COLLATE NOCASE";
                try (PreparedStatement pstmt = connection.prepareStatement(updateUsernameSql)) {
                    pstmt.setString(1, newUsername);
                    pstmt.setString(2, oldUsername);
                    if (pstmt.executeUpdate() == 0) {
                        return RelinkResult.SOURCE_NOT_FOUND;
                    }
                }
                return RelinkResult.SUCCESS;
            });

            switch (result) {
                case SUCCESS -> {
                }
                case SOURCE_NOT_FOUND -> throw new UserNotFoundException();
                case TARGET_ALREADY_LINKED -> throw new MinecraftUsernameAlreadyLinkedException();
            }
        } catch (SQLException e) {
            if (isConstraintViolation(e)) {
                throw new MinecraftUsernameAlreadyLinkedException();
            }
            throw new IllegalStateException("Failed to relink Minecraft account", e);
        }
    }

    private static boolean isConstraintViolation(SQLException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalizedMessage = message.toLowerCase(java.util.Locale.ROOT);
        return normalizedMessage.contains("sqlite_constraint_primarykey") ||
                normalizedMessage.contains("sqlite_constraint_unique") ||
                normalizedMessage.contains("unique constraint failed");
    }

    public synchronized void updateLastTimeUserReceivedCode(String discordId, String ip) {
        String sql = "INSERT INTO verification_history (discord_id, ip_address, last_received) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, ip);
            pstmt.setTimestamp(3, Timestamp.from(Instant.now()));
            pstmt.executeUpdate();
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
    }

    public synchronized long getSecondsSinceLastCode(String discordId, String ip) throws NoCodesFoundException {
        String sql = "SELECT last_received FROM verification_history WHERE discord_id = ? AND ip_address = ? ORDER BY last_received DESC LIMIT 1";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, ip);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                Timestamp last = rs.getTimestamp("last_received");
                return java.time.Duration.between(last.toInstant(), Instant.now()).getSeconds();
            }
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
        throw new NoCodesFoundException();
    }

    public synchronized IpDataCleanupResult purgeExpiredIpData(int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("IP data retention must be at least one day");
        }

        Timestamp cutoff = Timestamp.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        try {
            return databaseService.executeInTransaction(connection -> {
                int orphanUsersDeleted;
                String deleteOrphanUsers = """
                        DELETE FROM users
                        WHERE NOT EXISTS (
                            SELECT 1 FROM linked_accounts WHERE linked_accounts.discord_id = users.discord_id
                        )
                        """;
                try (PreparedStatement statement = connection.prepareStatement(deleteOrphanUsers)) {
                    orphanUsersDeleted = statement.executeUpdate();
                }

                int historicalIpsDeleted;
                String deleteHistoricalIps = """
                        DELETE FROM user_ips
                        WHERE (
                              last_seen IS NULL
                              OR (typeof(last_seen) IN ('integer', 'real') AND last_seen < ?)
                              OR (typeof(last_seen) = 'text' AND datetime(last_seen) < datetime(?))
                          )
                          AND NOT EXISTS (
                              SELECT 1
                              FROM users
                              WHERE users.discord_id = user_ips.discord_id
                                AND users.current_allowed_ip = user_ips.ip_address
                          )
                        """;
                try (PreparedStatement statement = connection.prepareStatement(deleteHistoricalIps)) {
                    statement.setLong(1, cutoff.getTime());
                    statement.setString(2, cutoff.toString());
                    historicalIpsDeleted = statement.executeUpdate();
                }

                int verificationRecordsDeleted;
                String deleteVerificationHistory = """
                        DELETE FROM verification_history
                        WHERE (typeof(last_received) IN ('integer', 'real') AND last_received < ?)
                           OR (typeof(last_received) = 'text' AND datetime(last_received) < datetime(?))
                        """;
                try (PreparedStatement statement = connection.prepareStatement(deleteVerificationHistory)) {
                    statement.setLong(1, cutoff.getTime());
                    statement.setString(2, cutoff.toString());
                    verificationRecordsDeleted = statement.executeUpdate();
                }

                return new IpDataCleanupResult(
                        orphanUsersDeleted,
                        historicalIpsDeleted,
                        verificationRecordsDeleted
                );
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to purge expired IP data", e);
        }
    }

    public synchronized void onShutDown() { databaseService.closeConnection(); }
}
