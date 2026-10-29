package com.anti.houseepg.guide.policy;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The comparison key two providers' spellings of the same channel collapse to (ADR-0003).
 *
 * <p>A country prefix ({@code HU - }), a country suffix ({@code .hu}) and quality tags
 * ({@code (HD)}, {@code HD}, {@code (FHD)}, {@code (SD)}, {@code 4K}, {@code UHD}, ...) are stripped,
 * then every separator, so word-spacing differences ({@code AnimalPlanet} vs {@code Animal Planet})
 * also collapse.
 */
public record ChannelKey(String value) {

    private static final Pattern COUNTRY_PREFIX = Pattern.compile("^\\s*[A-Za-z]{2}\\s*[-:|]\\s*");
    private static final Pattern COUNTRY_SUFFIX = Pattern.compile("\\.[A-Za-z]{2,3}$");
    private static final Pattern QUALITY_TAG =
            Pattern.compile("(?i)(?<![A-Za-z0-9])(uhd|fhd|hd|sd|h265|hevc|4k|8k)(?![A-Za-z0-9])");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

    public static ChannelKey of(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ChannelKey("");
        }
        String text = raw.trim();

        var prefix = COUNTRY_PREFIX.matcher(text);
        if (prefix.find() && HAS_LETTER.matcher(text.substring(prefix.end())).find()) {
            text = prefix.replaceFirst("");
        }

        var suffix = COUNTRY_SUFFIX.matcher(text);
        // "9.Tv" must keep its "Tv": a country suffix always leaves letters behind.
        if (suffix.find() && HAS_LETTER.matcher(text.substring(0, suffix.start())).find()) {
            text = suffix.replaceFirst("");
        }

        text = QUALITY_TAG.matcher(text).replaceAll(" ");
        return new ChannelKey(NON_ALNUM.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(""));
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }
}
