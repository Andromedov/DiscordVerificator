package net.justempire.discordverificator.models;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonAutoDetect
public class User {
    @JsonProperty("discordId")
    private String discordId;

    @JsonProperty("linkedMinecraftUsernames")
    public List<String> linkedMinecraftUsernames;

    @JsonProperty("latestVerificationsFromIps")
    private List<LastTimeUserReceivedCode> latestVerificationsFromIps;

    @JsonProperty("currentAllowedIp")
    private String currentAllowedIp;

    private boolean isBlocked;
    private boolean allowSharedIp;
    private boolean manualAccessBypass;

    // Empty constructor for Jackson
    public User() { }

    public User(String discordUsername, List<String> minecraftUsernames,
                List<LastTimeUserReceivedCode> latestVerificationsFromIps,
                String currentAllowedIp, boolean isBlocked, boolean allowSharedIp,
                boolean manualAccessBypass) {
        this.discordId = discordUsername;
        this.linkedMinecraftUsernames = new ArrayList<>(minecraftUsernames);
        this.latestVerificationsFromIps = latestVerificationsFromIps;
        this.currentAllowedIp = currentAllowedIp;
        this.isBlocked = isBlocked;
        this.allowSharedIp = allowSharedIp;
        this.manualAccessBypass = manualAccessBypass;
    }

    public String getDiscordId() {
        return discordId;
    }

    public String getCurrentAllowedIp() {
        return currentAllowedIp;
    }

    public boolean isBlocked() {
        return isBlocked;
    }

    public boolean isSharedIpAllowed() {
        return allowSharedIp;
    }

    public boolean isManualAccessBypassEnabled() {
        return manualAccessBypass;
    }
}
