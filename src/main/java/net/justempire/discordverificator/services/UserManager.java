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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class UserManager {
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
    public synchronized void setUserBlocked(String discordId, boolean blocked) {
        String sql = "UPDATE users SET is_blocked = ? WHERE discord_id = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, blocked ? 1 : 0);
            pstmt.setString(2, discordId);
            pstmt.executeUpdate();
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
    }

    // Set Allow Shared IP Status
    public synchronized void setAllowSharedIp(String discordId, boolean allowed) {
        String sql = "UPDATE users SET allow_shared_ip = ? WHERE discord_id = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, allowed ? 1 : 0);
            pstmt.setString(2, discordId);
            pstmt.executeUpdate();
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
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
        try { upsertUser(discordId, ""); } catch (SQLException e) { return; }

        String sql = "INSERT INTO linked_accounts (minecraft_username, discord_id, linked_at) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, minecraftUsername);
            pstmt.setString(2, discordId);
            pstmt.setTimestamp(3, Timestamp.from(Instant.now()));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            if (e.getMessage().contains("PRIMARY KEY") || e.getMessage().contains("constraint")) {
                throw new MinecraftUsernameAlreadyLinkedException();
            }
            logger.log(Level.SEVERE, "Database error", e);
        }
    }

    public synchronized void unlinkUser(String minecraftUsername) throws NotFoundException {
        String sql = "DELETE FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, minecraftUsername);
            int rows = pstmt.executeUpdate();
            if (rows == 0) throw new NotFoundException();
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }
    }

    public synchronized void relinkUser(String oldUsername, String newUsername) throws UserNotFoundException, MinecraftUsernameAlreadyLinkedException {
        String discordId;
        Timestamp linkedAt;
        Timestamp lastLogin;

        String sqlSelect = "SELECT discord_id, linked_at, last_login FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
        // Retrieves user linkage data or throws not found exception
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sqlSelect)) {
            pstmt.setString(1, oldUsername);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                discordId = rs.getString("discord_id");
                linkedAt = rs.getTimestamp("linked_at");
                lastLogin = rs.getTimestamp("last_login");
            } else throw new UserNotFoundException();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error", e);
            throw new UserNotFoundException();
        }

        String sqlCheck = "SELECT discord_id FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sqlCheck)) {
        // Verifies new username availability or throws MinecraftUsernameAlreadyLinkedException
            pstmt.setString(1, newUsername);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) throw new MinecraftUsernameAlreadyLinkedException();
        } catch (SQLException e) { logger.log(Level.SEVERE, "Database error", e); }

        try { unlinkUser(oldUsername); } catch (NotFoundException e) { throw new UserNotFoundException(); }

        String sqlInsert = "INSERT INTO linked_accounts (minecraft_username, discord_id, linked_at, last_login) VALUES (?, ?, ?, ?)";
        // Inserts linked account record; throws targeted exceptions on primary key or constraint violations
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sqlInsert)) {
            pstmt.setString(1, newUsername);
            pstmt.setString(2, discordId);
            pstmt.setTimestamp(3, linkedAt != null ? linkedAt : Timestamp.from(Instant.now()));
            pstmt.setTimestamp(4, lastLogin);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            if (e.getMessage().contains("PRIMARY KEY") || e.getMessage().contains("constraint")) throw new MinecraftUsernameAlreadyLinkedException();
            logger.log(Level.SEVERE, "Database error", e);
        }
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

    public synchronized void onShutDown() { databaseService.closeConnection(); }
}
