package com.jitong.im.auth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class PublicNumberGenerator {

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder digits = new StringBuilder(10);
        digits.append(random.nextInt(9) + 1);
        for (int index = 1; index < 10; index++) {
            digits.append(random.nextInt(10));
        }
        return PublicNumber.withCheckDigit(digits.toString());
    }
}
