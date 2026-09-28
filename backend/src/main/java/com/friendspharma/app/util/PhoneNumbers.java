package com.friendspharma.app.util;

/**
 * One spelling for a mobile number, so "017…", "+88017…" and "88017…" are the
 * same identity.
 *
 * <p>Digits only. A Bangladeshi mobile written the local way (11 digits from
 * 01) or with the country code (13 digits from 8801) is stored with the
 * country code; any other 8–15 digit number is kept as its digits. The rule is
 * deliberately narrow: it must never turn two different numbers into one.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /** The canonical digits, or null when the input is not a phone number. */
    public static String normalise(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return null;
        if (d.length() == 11 && d.startsWith("01")) d = "88" + d;
        else if (d.length() == 10 && d.startsWith("1"))  d = "880" + d;
        if (d.length() < 8 || d.length() > 15) return null;
        return d;
    }

    public static boolean isValid(String raw) {
        return normalise(raw) != null;
    }

    /** How the ERP's own customer rows spell a Bangladeshi number: the local 11 digits. */
    public static String localForm(String canonical) {
        if (canonical == null) return null;
        if (canonical.length() == 13 && canonical.startsWith("8801")) return canonical.substring(2);
        return canonical;
    }
}
