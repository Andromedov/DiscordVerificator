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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

// Performs actions on users and loads/saves from/to SQLite
public class UserManager {
    private final DatabaseService databaseService;
    private final Logger logger;
    private final String jsonPath;

    public UserManager(DatabaseService databaseService, String jsonPath, Logger logger) {
        this.databaseService = databaseService;
        this.jsonPath = jsonPath;
        this.logger = logger;

        // Attempt migration on startup
        migrateFromJson();
    }

    // --- MIGRATION LOGIC ---
    private void migrateFromJson() {
        File jsonFile = new File(jsonPath);
        if (!jsonFile.exists()) return;

        logger.info("Found users.json! Starting migration to SQLite...");

        ObjectMapper mapper = new ObjectMapper();
        try {
            List<User> oldUsers = mapper.readValue(jsonFile, new TypeReference<List<User>>() {});

            int migratedCount = 0;
            for (User oldUser : oldUsers) {
                try {
                    // 1. Insert User
                    upsertUser(oldUser.getDiscordId(), oldUser.getCurrentAllowedIp());

                    // 2. Insert Linked Accounts
                    for (String mcName : oldUser.linkedMinecraftUsernames) {
                        try {
                            linkUser(oldUser.getDiscordId(), mcName);
                        } catch (MinecraftUsernameAlreadyLinkedException ignored) {}
                    }

                    // 3. Insert History (Best effort)
                    migratedCount++;
                } catch (Exception e) { logger.warning("Failed to migrate user: " + oldUser.getDiscordId()); e.printStackTrace(); }
            }

            // Rename JSON file so we don't migrate again
            File renamed = new File(jsonPath + ".old");
            jsonFile.renameTo(renamed);
            logger.info("Renamed users.json to users.json.old. Migrated " + migratedCount + " users.");
        } catch (IOException e) { e.printStackTrace(); }
    }

    // --- CORE DATABASE METHODS ---

    // Upsert: Update if exists, Insert if not
    private void upsertUser(String discordId, String currentIp) throws SQLException {
        String sql = "INSERT INTO users (discord_id, current_allowed_ip) VALUES(?, ?) " +
                "ON CONFLICT(discord_id) DO UPDATE SET current_allowed_ip = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, currentIp);
            pstmt.setString(3, currentIp);
            pstmt.executeUpdate();
        }
    }

    public String getDiscordIdByMinecraftUsername(String minecraftUsername) throws UserNotFoundException {
        String sql = "SELECT discord_id FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, minecraftUsername);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("discord_id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        throw new UserNotFoundException();
    }

    public User getFullUserByDiscordId(String discordId) throws UserNotFoundException {
        String sql = "SELECT * FROM users WHERE discord_id = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                String id = rs.getString("discord_id");
                String ip = rs.getString("current_allowed_ip");

                return new User(
                        id,
                        getLinkedAccounts(id),
                        null,
                        ip
                );
            }
        } catch (SQLException e) { e.printStackTrace(); }
        throw new UserNotFoundException();
    }

    // --- /DVINFO COMMAND ---
    public Map<String, String> getPlayerInfo(String minecraftUsername) throws UserNotFoundException {
        String sql = "SELECT l.discord_id, l.last_login, u.current_allowed_ip " +
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

                Timestamp lastLogin = rs.getTimestamp("last_login");
                info.put("last_login", lastLogin != null ? lastLogin.toString() : "Never/Unknown");

                return info;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        throw new UserNotFoundException();
    }

    // --- UPDATING LOGIN TIME ---
    public void updatePlayerLoginTime(String minecraftUsername) {
        String sql = "UPDATE linked_accounts SET last_login = ? WHERE minecraft_username = ? COLLATE NOCASE";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setTimestamp(1, Timestamp.from(Instant.now()));
            pstmt.setString(2, minecraftUsername);
            pstmt.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private List<String> getLinkedAccounts(String discordId) {
        List<String> accounts = new java.util.ArrayList<>();
        String sql = "SELECT minecraft_username FROM linked_accounts WHERE discord_id = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) { accounts.add(rs.getString("minecraft_username")); }
        } catch (SQLException e) { e.printStackTrace(); }
        return accounts;
    }

    public void updateIp(String discordId, String newIp) throws UserNotFoundException {
        String sql = "UPDATE users SET current_allowed_ip = ? WHERE discord_id = ?";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, newIp);
            pstmt.setString(2, discordId);
            int affected = pstmt.executeUpdate();
            if (affected == 0) throw new UserNotFoundException();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void linkUser(String discordId, String minecraftUsername) throws MinecraftUsernameAlreadyLinkedException {
        try {
            upsertUser(discordId, ""); // Create if not exists with empty IP
        } catch (SQLException e) { e.printStackTrace(); return; }

        String sql = "INSERT INTO linked_accounts (minecraft_username, discord_id) VALUES (?, ?)";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, minecraftUsername);
            pstmt.setString(2, discordId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            if (e.getMessage().contains("PRIMARY KEY") || e.getMessage().contains("constraint")) {
                throw new MinecraftUsernameAlreadyLinkedException();
            }
            e.printStackTrace();
        }
    }

    public void unlinkUser(String minecraftUsername) throws NotFoundException {
        String sql = "DELETE FROM linked_accounts WHERE minecraft_username = ? COLLATE NOCASE";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, minecraftUsername);
            int rows = pstmt.executeUpdate();
            if (rows == 0) throw new NotFoundException();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    // --- HISTORY / SPAM PREVENTION LOGIC ---
    public void updateLastTimeUserReceivedCode(String discordId, String ip) {
        String sql = "INSERT INTO verification_history (discord_id, ip_address, last_received) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, ip);
            pstmt.setTimestamp(3, Timestamp.from(Instant.now()));
            pstmt.executeUpdate();

            // "DELETE FROM verification_history WHERE last_received < date('now', '-1 day')"
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public long getSecondsSinceLastCode(String discordId, String ip) throws NoCodesFoundException {
        String sql = "SELECT last_received FROM verification_history WHERE discord_id = ? AND ip_address = ? ORDER BY last_received DESC LIMIT 1";
        try (PreparedStatement pstmt = databaseService.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, ip);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                Timestamp last = rs.getTimestamp("last_received");
                long secondsDiff = java.time.Duration.between(last.toInstant(), Instant.now()).getSeconds();
                return secondsDiff;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        throw new NoCodesFoundException();
    }

    public void onShutDown() { databaseService.closeConnection(); }
}