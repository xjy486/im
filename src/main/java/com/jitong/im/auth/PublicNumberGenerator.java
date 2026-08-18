package com.jitong.im.auth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.Supplier;

@Component
public class PublicNumberGenerator {

    private final Supplier<String> candidateSupplier;

    public PublicNumberGenerator() {
        this(new SecureRandom());
    }

    public PublicNumberGenerator(Supplier<String> candidateSupplier) {
        this.candidateSupplier = Objects.requireNonNull(candidateSupplier, "candidateSupplier");
    }

    private PublicNumberGenerator(SecureRandom random) {
        this(() -> randomCandidate(random));
    }

    public String next() {
        return candidateSupplier.get();
    }

    private static String randomCandidate(SecureRandom random) {
        StringBuilder digits = new StringBuilder(10);
        digits.append(random.nextInt(9) + 1);
        for (int index = 1; index < 10; index++) {
            digits.append(random.nextInt(10));
        }
        return PublicNumber.withCheckDigit(digits.toString());
    }
}
