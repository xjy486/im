package com.jitong.im.auth;

import java.util.Objects;

public final class PublicNumber {

    private PublicNumber() {
    }

    public static boolean isValid(String value) {
        if (value == null || !value.matches("[1-9][0-9]{10}")) {
            return false;
        }

        return checksum(value, false) % 10 == 0;
    }

    public static String withCheckDigit(String tenDigits) {
        Objects.requireNonNull(tenDigits, "tenDigits");
        if (!tenDigits.matches("[1-9][0-9]{9}")) {
            throw new IllegalArgumentException("tenDigits must be ten digits and start with a non-zero digit");
        }

        return tenDigits + ((10 - (checksum(tenDigits, true) % 10)) % 10);
    }

    private static int checksum(String digits, boolean doubleRightmost) {
        int sum = 0;
        boolean doubleDigit = doubleRightmost;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubleDigit) {
                digit = digit * 2 > 9 ? digit * 2 - 9 : digit * 2;
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum;
    }
}
