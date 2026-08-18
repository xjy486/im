package com.jitong.im.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicNumberTest {

    @Test
    void generates_and_validates_luhn_numbers() {
        PublicNumberGenerator generator = new PublicNumberGenerator();

        String number = generator.next();

        assertThat(number).hasSize(11).matches("[1-9][0-9]{10}");
        assertThat(PublicNumber.isValid(number)).isTrue();
    }

    @Test
    void rejects_numbers_with_invalid_shape_or_check_digit() {
        assertThat(PublicNumber.isValid("01234567890")).isFalse();
        assertThat(PublicNumber.isValid("12345678901")).isFalse();
        assertThat(PublicNumber.isValid("1234567890")).isFalse();
    }

    @Test
    void uses_version_seven_for_internal_identifiers() {
        assertThat(UuidV7.random().version()).isEqualTo(7);
    }
}
