package com.friendspharma.app.security;

import java.security.SecureRandom;

/**
 * [V71] Generates the one-time password a Super Admin hands to a user.
 *
 * <p>Two constraints pull against each other here. It has to be strong enough
 * that guessing it is not worth attempting, and it has to survive being read
 * down a phone line to a pharmacist — because that is how it will actually be
 * delivered, whatever the policy says. A password that gets mistyped four times
 * ends with the Super Admin reading out something simpler instead, which is a
 * worse password than the one this generates.
 *
 * <p>So the alphabet drops the characters that are ambiguous when spoken or
 * read: no O or 0, no I, l or 1, no S or 5, no B or 8, no Z or 2. What is left
 * is unambiguous aloud, and the loss is paid back by length rather than by
 * relying on the user to hear the difference.
 *
 * <p>Grouped into blocks with hyphens, which is dictation-friendly and does not
 * reduce the entropy — the separators are in a fixed position and are not part
 * of what an attacker has to guess.
 */
public final class TemporaryPassword {

    /* 23 consonant-and-vowel letters plus 6 digits, ambiguous ones removed. */
    private static final String ALPHABET = "ACDEFGHJKMNPQRTUVWXY" + "34679";
    private static final int GROUPS = 4;
    private static final int PER_GROUP = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPassword() {}

    /**
     * A new temporary password, in the form {@code ACDE-FGHJ-KMNP-QRTU}.
     *
     * <p>16 characters from an alphabet of 25 is about 74 bits, which is far
     * beyond what a password expiring in hours needs, and costs nothing.
     */
    public static String generate() {
        StringBuilder sb = new StringBuilder(GROUPS * PER_GROUP + GROUPS - 1);
        for (int g = 0; g < GROUPS; g++) {
            if (g > 0) sb.append('-');
            for (int i = 0; i < PER_GROUP; i++) {
                /* nextInt(bound), not nextInt() % bound: the modulus is biased
                   toward the low end of the alphabet whenever the bound does not
                   divide the range, and biasing a password generator is the kind
                   of defect that never shows up in testing. */
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
        }
        return sb.toString();
    }
}
