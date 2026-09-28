package com.friendspharma.app.entity;

import java.util.List;
import java.util.Locale;

/**
 * What an employee does.
 *
 * <p>Stored as a string rather than a JPA enum so the database stays readable
 * and a value added here does not require a schema change — but the set is
 * closed by a CHECK constraint in {@code V12}, so this list and that constraint
 * must agree. {@link #isValid(String)} is the guard that keeps a bad value from
 * reaching the database and failing as a constraint violation instead of a
 * clear message.
 *
 * <p>Only {@link #DELIVERY_MAN} carries behaviour: it decides who may be handed
 * an invoice to deliver. The rest exist so the column can describe the whole
 * workforce rather than splitting it into "driver" and "not driver".
 */
public final class EmployeeType {

    private EmployeeType() {}

    public static final String DELIVERY_MAN = "DELIVERY_MAN";
    public static final String SALES_REP    = "SALES_REP";
    public static final String CASHIER      = "CASHIER";
    public static final String PHARMACIST   = "PHARMACIST";
    public static final String MANAGER      = "MANAGER";
    public static final String ACCOUNTANT   = "ACCOUNTANT";

    /**
     * Unclassified.
     *
     * <p>Deliberately inert: it grants nothing and appears in no picker, so an
     * employee nobody has reviewed cannot be assigned a delivery by accident.
     * The migration backfilled every employee without a vehicle number to this.
     */
    public static final String OTHER = "OTHER";

    public static final List<String> ALL = List.of(
            DELIVERY_MAN, SALES_REP, CASHIER, PHARMACIST, MANAGER, ACCOUNTANT, OTHER);

    public static boolean isValid(String type) {
        return type != null && ALL.contains(type);
    }

    /**
     * Accepts what a form is likely to send — lower case, spaces or hyphens for
     * underscores — and returns null for anything not in the set.
     *
     * <p>Null rather than a fallback: quietly turning an unrecognised type into
     * OTHER would hide a client bug, and turning it into DELIVERY_MAN would put
     * the wrong person on a delivery run.
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return ALL.contains(t) ? t : null;
    }

    /** "DELIVERY_MAN" → "Delivery Man", for messages and pickers. */
    public static String label(String type) {
        if (type == null) return "";
        String[] words = type.split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.charAt(0)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
