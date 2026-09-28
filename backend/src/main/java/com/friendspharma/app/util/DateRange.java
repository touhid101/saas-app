package com.friendspharma.app.util;

import java.time.LocalDate;

/**
 * Open-ended date bounds, expressed as real dates.
 *
 * <p>PostgreSQL cannot infer the type of a bare null parameter, so the natural
 * JPQL idiom
 *
 * <pre>{@code AND (:from IS NULL OR t.transactionDate >= :from)}</pre>
 *
 * fails at runtime with <em>could not determine data type of parameter</em> the
 * moment a date is actually supplied — and only then. Every list in this
 * application that offered a date range carried that fault: the filters looked
 * present, returned everything while the boxes were empty, and threw a 500 as
 * soon as anyone used them.
 *
 * <p>Substituting bounds removes the null instead of trying to type it, which
 * is the same trick the string filters already use with {@code ''}. Both
 * columns filtered this way are NOT NULL, so a bounded comparison selects
 * exactly the rows an is-null check would have.
 */
public final class DateRange {

    /** Comfortably before any transaction this system will hold. */
    private static final LocalDate DAWN = LocalDate.of(1900, 1, 1);
    /** The maximum a PostgreSQL {@code date} column accepts without overflow. */
    private static final LocalDate DUSK = LocalDate.of(9999, 12, 31);

    private DateRange() {}

    /** The lower bound, or the beginning of time when none was given. */
    public static LocalDate since(LocalDate from) {
        return from != null ? from : DAWN;
    }

    /** The upper bound, or the end of time when none was given. */
    public static LocalDate until(LocalDate to) {
        return to != null ? to : DUSK;
    }
}
