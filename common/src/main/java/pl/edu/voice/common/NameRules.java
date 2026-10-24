package pl.edu.voice.common;

import java.util.regex.Pattern;

/** Rules shared by the client form validation and the authoritative server validation. */
public final class NameRules {
    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,40}");

    private NameRules() {
    }

    public static boolean isValid(String value) {
        return value != null && SAFE_NAME.matcher(value).matches();
    }

    public static void requireValid(String label, String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException(
                    label + " może mieć 1–40 liter, cyfr, _ lub -");
        }
    }
}
