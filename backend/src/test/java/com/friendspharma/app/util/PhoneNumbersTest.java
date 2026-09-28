package com.friendspharma.app.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One spelling per number. The e-commerce identity is unique on this value,
 * so the rule must merge every way the same number is typed and never merge
 * two different numbers.
 */
class PhoneNumbersTest {

    @Test
    @DisplayName("local, +88 and 88 spellings of one Bangladeshi mobile are one identity")
    void bangladeshiSpellings() {
        assertThat(PhoneNumbers.normalise("01712345678")).isEqualTo("8801712345678");
        assertThat(PhoneNumbers.normalise("+880 1712-345678")).isEqualTo("8801712345678");
        assertThat(PhoneNumbers.normalise("8801712345678")).isEqualTo("8801712345678");
        assertThat(PhoneNumbers.normalise("1712345678")).isEqualTo("8801712345678");
    }

    @Test
    @DisplayName("the ERP local form is the 11-digit spelling the pharmacy rows use")
    void localForm() {
        assertThat(PhoneNumbers.localForm("8801712345678")).isEqualTo("01712345678");
        assertThat(PhoneNumbers.localForm("4412345678")).isEqualTo("4412345678");
    }

    @Test
    @DisplayName("rubbish and too-short input is not a number")
    void invalid() {
        assertThat(PhoneNumbers.normalise("")).isNull();
        assertThat(PhoneNumbers.normalise("abc")).isNull();
        assertThat(PhoneNumbers.normalise("12345")).isNull();
        assertThat(PhoneNumbers.normalise(null)).isNull();
        assertThat(PhoneNumbers.isValid("017")).isFalse();
    }

    @Test
    @DisplayName("two different numbers never normalise to the same value")
    void distinct() {
        assertThat(PhoneNumbers.normalise("01712345678")).isNotEqualTo(PhoneNumbers.normalise("01712345679"));
        assertThat(PhoneNumbers.normalise("02112345678")).isNotEqualTo(PhoneNumbers.normalise("01112345678"));
    }
}
