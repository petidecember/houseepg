package com.anti.houseepg.match;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a source channel name or id into a comparison key, so the same real channel from different
 * providers matches. See ADR-0003.
 *
 * <p>Strips a country prefix ({@code HU - }), a country suffix ({@code .hu}), and quality tags
 * ({@code (HD)}, {@code HD}, {@code (FHD)}, {@code (SD)}, {@code 4K}, {@code UHD}, ...), then removes
 * every separator so word spacing differences ({@code AnimalPlanet} vs {@code Animal Planet}) collapse.
 */
public final class ChannelNameNormalizer {

    private static final Pattern COUNTRY_PREFIX = Pattern.compile("^\\s*[A-Za-z]{2}\\s*[-:|]\\s*");
    private static final Pattern COUNTRY_SUFFIX = Pattern.compile("\\.[A-Za-z]{2,3}$");
    private static final Pattern QUALITY_TAG =
            Pattern.compile("(?i)(?<![A-Za-z0-9])(uhd|fhd|hd|sd|h265|hevc|4k|8k)(?![A-Za-z0-9])");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

    private ChannelNameNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();

        var prefix = COUNTRY_PREFIX.matcher(value);
        if (prefix.find() && HAS_LETTER.matcher(value.substring(prefix.end())).find()) {
            value = prefix.replaceFirst("");
        }

        var suffix = COUNTRY_SUFFIX.matcher(value);
        if (suffix.find() && HAS_LETTER.matcher(value.substring(0, suffix.start())).find()) {
            value = suffix.replaceFirst("");
        }

        value = QUALITY_TAG.matcher(value).replaceAll(" ");
        value = value.toLowerCase(Locale.ROOT);
        return NON_ALNUM.matcher(value).replaceAll("");
    }
}
