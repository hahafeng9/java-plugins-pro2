package com.example.pro.runtime;

import java.security.SecureRandom;

/**
 * Short, filesystem-safe random identifiers used for temp directories and temp file names.
 *
 * The alphabet is lowercase letters and digits only: valid on Windows and Linux, no path
 * separators, no shell metacharacters. Values come from {@link SecureRandom} and exist purely to
 * avoid clashes between concurrent runs — they never carry data. Generated names are at least
 * six characters long in practice, which also avoids Windows reserved device names (CON, NUL...).
 */
public final class RandomToken {

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomToken() {
    }

    public static String generate(int length) {
        if (length < 1 || length > 64) {
            throw new IllegalArgumentException("length must be between 1 and 64, got " + length);
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
