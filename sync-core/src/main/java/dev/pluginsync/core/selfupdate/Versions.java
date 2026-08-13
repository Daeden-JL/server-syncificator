package dev.pluginsync.core.selfupdate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dot-separated numeric version comparison, e.g. for comparing a running version against a GitHub release tag. */
public final class Versions {

    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\d+");

    private Versions() {
    }

    /**
     * @return negative if {@code a < b}, positive if {@code a > b}, zero if equal. Missing trailing
     *     components compare as 0 ("0.2" == "0.2.0"). A non-numeric component (or one missing
     *     entirely, e.g. a malformed tag) also compares as 0 rather than throwing - a version check
     *     should degrade to "no update found", never crash the caller.
     */
    public static int compare(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            int cmp = Integer.compare(component(partsA, i), component(partsB, i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int component(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        Matcher matcher = LEADING_DIGITS.matcher(parts[index]);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }
}
