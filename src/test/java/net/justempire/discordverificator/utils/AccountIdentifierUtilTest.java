package net.justempire.discordverificator.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountIdentifierUtilTest {
    @Test
    void parsesAndCanonicalizesMinecraftUsernames() {
        assertEquals("abc", AccountIdentifierUtil.parseMinecraftUsername(" AbC ").orElseThrow());
        assertEquals(
                "player_name_1234",
                AccountIdentifierUtil.parseMinecraftUsername("Player_Name_1234").orElseThrow()
        );
        assertEquals(
                ".bedrock_player",
                AccountIdentifierUtil.parseMinecraftUsername(" .Bedrock_Player ").orElseThrow()
        );
    }

    @Test
    void addsConfiguredFloodgatePrefixToBedrockUsername() {
        assertEquals(
                ".bedrockplayer",
                AccountIdentifierUtil.parseBedrockUsername("BedrockPlayer", ".").orElseThrow()
        );
        assertEquals(
                ".bedrockplayer",
                AccountIdentifierUtil.parseBedrockUsername(".BedrockPlayer", ".").orElseThrow()
        );
        assertEquals(
                "*bedrockplayer",
                AccountIdentifierUtil.parseBedrockUsername("BedrockPlayer", "*").orElseThrow()
        );
    }

    @Test
    void rejectsDotsOutsideConfiguredFloodgatePrefix() {
        assertTrue(AccountIdentifierUtil.parseMinecraftUsername("Bed.rock").isEmpty());
        assertTrue(AccountIdentifierUtil.parseMinecraftUsername("..Bedrock").isEmpty());
        assertTrue(AccountIdentifierUtil.parseBedrockUsername(".Bedrock", "*").isEmpty());
    }

    @Test
    void acceptsMinecraftUsernameLengthBoundaries() {
        assertTrue(AccountIdentifierUtil.parseMinecraftUsername("abc").isPresent());
        assertTrue(AccountIdentifierUtil.parseMinecraftUsername("abcdefghijklmnop").isPresent());
    }

    @Test
    void rejectsInvalidMinecraftUsernames() {
        String[] invalidValues = {
                null,
                "",
                "ab",
                "abcdefghijklmnopq",
                "player-name",
                "player name",
                "гравець"
        };

        for (String value : invalidValues) {
            assertTrue(AccountIdentifierUtil.parseMinecraftUsername(value).isEmpty());
        }
    }

    @Test
    void parsesDiscordSnowflakeLengthBoundaries() {
        assertEquals(
                "12345678901234567",
                AccountIdentifierUtil.parseDiscordId(" 12345678901234567 ").orElseThrow()
        );
        assertEquals(
                "12345678901234567890",
                AccountIdentifierUtil.parseDiscordId("12345678901234567890").orElseThrow()
        );
    }

    @Test
    void rejectsInvalidDiscordIds() {
        String[] invalidValues = {
                null,
                "",
                "1234567890123456",
                "123456789012345678901",
                "1234567890123456a",
                "-12345678901234567"
        };

        for (String value : invalidValues) {
            assertTrue(AccountIdentifierUtil.parseDiscordId(value).isEmpty());
        }
    }

    @Test
    void classifiesDecisionTargetsWithoutArbitraryFallback() {
        AccountIdentifierUtil.ParsedTarget username =
                AccountIdentifierUtil.parseTarget("SomePlayer").orElseThrow();
        AccountIdentifierUtil.ParsedTarget discordId =
                AccountIdentifierUtil.parseTarget("123456789012345678").orElseThrow();

        assertEquals(AccountIdentifierUtil.TargetType.MINECRAFT_USERNAME, username.type());
        assertEquals("someplayer", username.value());
        assertEquals(AccountIdentifierUtil.TargetType.DISCORD_ID, discordId.type());
        assertEquals("123456789012345678", discordId.value());
        AccountIdentifierUtil.ParsedTarget bedrockUsername =
                AccountIdentifierUtil.parseTarget(".BedrockPlayer").orElseThrow();
        assertEquals(AccountIdentifierUtil.TargetType.MINECRAFT_USERNAME, bedrockUsername.type());
        assertEquals(".bedrockplayer", bedrockUsername.value());
        assertTrue(AccountIdentifierUtil.parseTarget("arbitrary.target").isEmpty());
    }
}
