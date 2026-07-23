package net.justempire.discordverificator.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IpAddressUtilTest {
    @Test
    void masksIpv4Addresses() {
        assertEquals("192.168.*.*", IpAddressUtil.mask("192.168.10.25"));
        assertEquals("8.8.*.*", IpAddressUtil.mask("8.8.8.8"));
    }

    @Test
    void masksIpv6Addresses() {
        assertEquals("2001:db8:…", IpAddressUtil.mask("2001:db8:85a3::8a2e:370:7334"));
        assertEquals("fe80:1:…", IpAddressUtil.mask("fe80:1::abcd"));
        assertEquals("…:…", IpAddressUtil.mask("::1"));
    }

    @Test
    void malformedValuesFailClosed() {
        assertEquals("***", IpAddressUtil.mask("999.999.999.999"));
        assertEquals("***", IpAddressUtil.mask("not-an-ip"));
        assertEquals("unknown", IpAddressUtil.mask(" "));
    }

    @Test
    void fullAddressRequiresExplicitlyDisabledMasking() {
        assertEquals("203.0.113.42", IpAddressUtil.displayForStaff("203.0.113.42", false));
        assertEquals("203.0.*.*", IpAddressUtil.displayForStaff("203.0.113.42", true));
    }
}
