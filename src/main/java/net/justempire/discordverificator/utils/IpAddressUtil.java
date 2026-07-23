package net.justempire.discordverificator.utils;

import java.util.ArrayList;
import java.util.List;

public final class IpAddressUtil {
    private IpAddressUtil() {
    }

    public static String displayForStaff(String ipAddress, boolean maskIpAddresses) {
        return maskIpAddresses ? mask(ipAddress) : ipAddress;
    }

    public static String mask(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return "unknown";
        }

        String normalized = ipAddress.trim();
        String[] ipv4Parts = normalized.split("\\.", -1);
        if (ipv4Parts.length == 4 && areValidIpv4Parts(ipv4Parts)) {
            return ipv4Parts[0] + "." + ipv4Parts[1] + ".*.*";
        }

        if (normalized.contains(":")) {
            if (normalized.startsWith("::")) {
                return "…:…";
            }

            List<String> visibleHextets = new ArrayList<>(2);
            for (String part : normalized.split(":", -1)) {
                if (!part.isEmpty()) {
                    if (!part.matches("[0-9A-Fa-f]{1,4}")) {
                        return "***";
                    }
                    visibleHextets.add(part);
                    if (visibleHextets.size() == 2) {
                        break;
                    }
                }
            }
            if (!visibleHextets.isEmpty()) {
                return String.join(":", visibleHextets) + ":…";
            }
        }

        return "***";
    }

    private static boolean areValidIpv4Parts(String[] parts) {
        for (String part : parts) {
            if (!part.matches("\\d{1,3}")) {
                return false;
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return false;
            }
        }
        return true;
    }
}
