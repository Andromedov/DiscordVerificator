package net.justempire.discordverificator.discord;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordBotModerationActionTest {
    @Test
    void parsesSupportedModerationActions() {
        Optional<DiscordModerationAction> allow =
                DiscordModerationAction.parse("dv_allow_12345678901234567");
        Optional<DiscordModerationAction> block =
                DiscordModerationAction.parse("dv_block_12345678901234567890");

        assertTrue(allow.isPresent());
        assertEquals(DiscordModerationAction.Type.ALLOW, allow.orElseThrow().type());
        assertEquals("12345678901234567", allow.orElseThrow().targetDiscordId());

        assertTrue(block.isPresent());
        assertEquals(DiscordModerationAction.Type.BLOCK, block.orElseThrow().type());
        assertEquals("12345678901234567890", block.orElseThrow().targetDiscordId());
    }

    @Test
    void rejectsMalformedOrUnsupportedModerationActions() {
        String[] invalidIds = {
                null,
                "",
                "dv_allow_1234567890123456",
                "dv_allow_123456789012345678901",
                "dv_allow_not-a-snowflake",
                "dv_allow_12345678901234567_extra",
                "dv_unblock_12345678901234567",
                "other_allow_12345678901234567"
        };

        for (String invalidId : invalidIds) {
            assertTrue(DiscordModerationAction.parse(invalidId).isEmpty(),
                    () -> "Expected component ID to be rejected: " + invalidId);
        }
    }
}
