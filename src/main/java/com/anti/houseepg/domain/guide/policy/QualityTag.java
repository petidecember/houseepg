package com.anti.houseepg.domain.guide.policy;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The video quality a channel name advertises: {@code (HD)}, {@code FHD}, {@code SD}, {@code 4K}.
 *
 * <p>{@link ChannelKey} strips these tags so two providers' spellings of one channel collapse; this
 * type keeps the tag, because the playlist decides whether HD and SD are genuinely separate channels
 * and a match between candidates that differ only by tag has to pick the right one (ADR-0003).
 */
public record QualityTag(String value) {

    private static final Pattern TAG =
            Pattern.compile("(?i)(?<![A-Za-z0-9])(uhd|fhd|hd|sd|h265|hevc|4k|8k)(?![A-Za-z0-9])");

    public static Optional<QualityTag> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        var matcher = TAG.matcher(raw);
        return matcher.find()
                ? Optional.of(new QualityTag(matcher.group(1).toLowerCase(Locale.ROOT)))
                : Optional.empty();
    }

    /** Removes every quality tag, leaving the separators behind so words cannot run together. */
    static String strip(String raw) {
        return TAG.matcher(raw).replaceAll(" ");
    }
}
