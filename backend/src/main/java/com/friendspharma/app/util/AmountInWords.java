package com.friendspharma.app.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A money amount, spelled out for the foot of an invoice.
 *
 * <p>Grouped the way Bangladesh counts — crore, lakh, thousand — rather than in
 * millions and billions. A customer in Mirpur reading "Taka Twelve Lakh" knows
 * the figure; "Taka One Point Two Million" is a translation exercise.
 *
 * <p>Poisha are included only when there are any. "Taka One Hundred Nineteen
 * Only" is what the counter writes; "and Zero Poisha" on every cash sale is
 * noise that makes the line harder to check, not easier.
 *
 * <p>Computed on the server so the A4 invoice and the till receipt cannot
 * disagree about the same sale — the whole reason both read one document.
 */
public final class AmountInWords {

    private AmountInWords() {}

    private static final String[] ONES = {
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
            "Seventeen", "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    /**
     * "Taka One Hundred Nineteen Only." — the whole line, ready to print.
     *
     * @param amount may be null, which prints as zero rather than blank: a
     *               missing total on an invoice is worse than a stated nil.
     */
    public static String format(BigDecimal amount) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);

        boolean negative = value.signum() < 0;
        value = value.abs();

        long taka = value.longValue();
        int poisha = value.subtract(BigDecimal.valueOf(taka))
                          .multiply(BigDecimal.valueOf(100))
                          .setScale(0, RoundingMode.HALF_UP)
                          .intValue();

        StringBuilder sb = new StringBuilder("Taka ");
        if (negative) sb.append("Minus ");
        sb.append(taka == 0 && poisha > 0 ? "" : words(taka));

        if (poisha > 0) {
            if (taka > 0) sb.append(" and ");
            sb.append(words(poisha)).append(" Poisha");
        }
        return sb.append(" Only.").toString().replaceAll("\\s+", " ").trim()
                 .replace(" .", ".");
    }

    /** The number alone, without the currency or the full stop. */
    public static String words(long n) {
        if (n == 0) return "Zero";

        StringBuilder sb = new StringBuilder();
        // Crore and lakh first: the grouping is 2-2-3, not 3-3-3.
        long crore = n / 10_000_000;  n %= 10_000_000;
        long lakh  = n / 100_000;     n %= 100_000;
        long thousand = n / 1_000;    n %= 1_000;
        long hundred  = n / 100;      n %= 100;

        if (crore > 0)    sb.append(words(crore)).append(" Crore ");
        if (lakh > 0)     sb.append(twoDigits(lakh)).append(" Lakh ");
        if (thousand > 0) sb.append(twoDigits(thousand)).append(" Thousand ");
        if (hundred > 0)  sb.append(ONES[(int) hundred]).append(" Hundred ");
        if (n > 0)        sb.append(twoDigits(n));

        return sb.toString().trim();
    }

    /** 0–99, where the teens are words of their own rather than ten-and-a-unit. */
    private static String twoDigits(long n) {
        if (n < 20) return ONES[(int) n];
        String tens = TENS[(int) (n / 10)];
        long unit = n % 10;
        return unit == 0 ? tens : tens + " " + ONES[(int) unit];
    }
}
