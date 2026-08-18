package com.jitong.im.auth;

import java.security.SecureRandom;
import java.util.UUID;

public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TIMESTAMP_MASK = 0x0000ffffffffffffL;
    private static final long RANDOM_B_MASK = 0x3fffffffffffffffL;

    private UuidV7() {
    }

    public static UUID random() {
        long timestamp = System.currentTimeMillis() & TIMESTAMP_MASK;
        long randomA = RANDOM.nextInt(1 << 12);
        long mostSignificantBits = (timestamp << 16) | 0x7000L | randomA;
        long leastSignificantBits = 0x8000000000000000L | (RANDOM.nextLong() & RANDOM_B_MASK);
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
