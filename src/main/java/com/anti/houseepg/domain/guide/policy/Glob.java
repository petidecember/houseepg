package com.anti.houseepg.domain.guide.policy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The one glob language the configuration uses, for a source's excluded channel ids (HOU-33) and for the
 * playlist's group whitelist (HOU-45).
 *
 * <p>Two wildcards and nothing else: {@code *} matches any run of characters including none, {@code ?}
 * matches exactly one. There is no character class, no alternation and no escape character, because a
 * channel id is not a file path and every extra feature is another way for a config typo to mean something
 * the operator did not intend. Everything that is not a wildcard is compared literally, which also means
 * the only pattern this class rejects is a blank one — there is no syntax left to get wrong.
 *
 * <p>Matching is case-insensitive: a provider is free to change the case of an id between fetches, and an
 * exclusion that quietly stopped matching after such a change would be worse than no exclusion at all.
 */
public final class Glob {

    private final String pattern;
    private final Pattern compiled;

    private Glob(String pattern, Pattern compiled) {
        this.pattern = pattern;
        this.compiled = compiled;
    }

    /** Compiles one configured pattern. A blank pattern is a configuration error, not a no-op. */
    public static Glob of(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("an exclusion pattern cannot be blank");
        }
        String trimmed = pattern.trim();
        return new Glob(trimmed, Pattern.compile(toRegex(trimmed), Pattern.CASE_INSENSITIVE));
    }

    /** Compiles a configured list. Null and empty both mean "nothing is excluded". */
    public static List<Glob> allOf(List<String> patterns) {
        return patterns == null ? List.of() : patterns.stream().map(Glob::of).toList();
    }

    public boolean matches(String value) {
        return value != null && compiled.matcher(value).matches();
    }

    public String pattern() {
        return pattern;
    }

    @Override
    public String toString() {
        return pattern;
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        int literalFrom = 0;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c != '*' && c != '?') {
                continue;
            }
            quote(regex, glob.substring(literalFrom, i));
            regex.append(c == '*' ? ".*" : ".");
            literalFrom = i + 1;
        }
        quote(regex, glob.substring(literalFrom));
        return regex.toString();
    }

    private static void quote(StringBuilder regex, String literal) {
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal));
        }
    }
}
