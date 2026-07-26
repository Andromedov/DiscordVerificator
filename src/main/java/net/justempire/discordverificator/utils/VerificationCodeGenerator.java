package net.justempire.discordverificator.utils;

import java.security.SecureRandom;

public final class VerificationCodeGenerator {
    private static final String CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private VerificationCodeGenerator() {
    }

    public static String generateVerificationCode(int codeLength) {
        if (codeLength <= 0) {
            throw new IllegalArgumentException("Code length must be positive");
        }

        StringBuilder code = new StringBuilder(codeLength);

        for (int i = 0; i < codeLength; i++) {
            int index = SECURE_RANDOM.nextInt(CHARACTERS.length());
            code.append(CHARACTERS.charAt(index));
        }

        return code.toString();
    }
}
