package com.friendspharma.app.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line at the foot of every invoice.
 *
 * <p>It is the figure a customer checks the numerals against and the one a
 * dispute is settled on, so it has to be right at the boundaries as well as in
 * the middle: the teens that are not ten-and-a-unit, the round hundreds, the
 * lakh and crore grouping this country counts in, and the poisha that should
 * appear only when there are any.
 */
class AmountInWordsTest {

    private static String of(String amount) {
        return AmountInWords.format(new BigDecimal(amount));
    }

    @Nested
    @DisplayName("the invoice in the brief")
    class TheWorkedExample {

        /** The receipt this was built from reads exactly this. */
        @Test
        @DisplayName("119 is One Hundred Nineteen")
        void oneHundredNineteen() {
            assertThat(of("119.00")).isEqualTo("Taka One Hundred Nineteen Only.");
        }
    }

    @Nested
    @DisplayName("boundaries")
    class Boundaries {

        @Test
        @DisplayName("zero still prints a figure")
        void zero() {
            assertThat(of("0")).isEqualTo("Taka Zero Only.");
        }

        /** Nineteen is a word, not "Ten Nine". */
        @Test
        @DisplayName("the teens are their own words")
        void teens() {
            assertThat(of("13")).isEqualTo("Taka Thirteen Only.");
            assertThat(of("19")).isEqualTo("Taka Nineteen Only.");
        }

        @Test
        @DisplayName("round tens carry no trailing unit")
        void roundTens() {
            assertThat(of("20")).isEqualTo("Taka Twenty Only.");
            assertThat(of("70")).isEqualTo("Taka Seventy Only.");
        }

        @Test
        @DisplayName("a round hundred says nothing after it")
        void roundHundred() {
            assertThat(of("100")).isEqualTo("Taka One Hundred Only.");
        }

        @Test
        @DisplayName("hundreds and tens read together")
        void hundredsAndTens() {
            assertThat(of("999")).isEqualTo("Taka Nine Hundred Ninety Nine Only.");
        }
    }

    @Nested
    @DisplayName("the way Bangladesh counts")
    class LakhAndCrore {

        /** Not "One Hundred Thousand". */
        @Test
        @DisplayName("a hundred thousand is one lakh")
        void lakh() {
            assertThat(of("100000")).isEqualTo("Taka One Lakh Only.");
        }

        @Test
        @DisplayName("ten million is one crore")
        void crore() {
            assertThat(of("10000000")).isEqualTo("Taka One Crore Only.");
        }

        /** The 2-2-3 grouping, all four scales at once. */
        @Test
        @DisplayName("crore, lakh, thousand and hundred together")
        void everyScale() {
            assertThat(of("12345678"))
                    .isEqualTo("Taka One Crore Twenty Three Lakh Forty Five Thousand Six Hundred Seventy Eight Only.");
        }
    }

    @Nested
    @DisplayName("poisha")
    class Poisha {

        /** Silent where there are none: every cash sale would otherwise carry it. */
        @Test
        @DisplayName("are omitted when the amount is whole")
        void omittedWhenZero() {
            assertThat(of("119.00")).doesNotContain("Poisha");
        }

        @Test
        @DisplayName("are spelled out when present")
        void spelledOut() {
            assertThat(of("119.50")).isEqualTo("Taka One Hundred Nineteen and Fifty Poisha Only.");
        }

        /** Under one taka there is no taka figure to read first. */
        @Test
        @DisplayName("stand alone below one taka")
        void aloneBelowOne() {
            assertThat(of("0.75")).isEqualTo("Taka Seventy Five Poisha Only.");
        }

        @Test
        @DisplayName("round to the nearest poisha rather than truncating")
        void rounds() {
            assertThat(of("10.567")).isEqualTo("Taka Ten and Fifty Seven Poisha Only.");
        }
    }

    @Nested
    @DisplayName("defensive")
    class Defensive {

        /** A missing total prints a stated nil, never a blank line. */
        @Test
        @DisplayName("null reads as zero")
        void nullIsZero() {
            assertThat(AmountInWords.format(null)).isEqualTo("Taka Zero Only.");
        }

        /** A credit note is a negative document and must say so. */
        @Test
        @DisplayName("a negative amount is marked")
        void negative() {
            assertThat(of("-50")).isEqualTo("Taka Minus Fifty Only.");
        }
    }
}
