package com.anti.houseepg.m3u;

import com.anti.houseepg.domain.CanonicalChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the playlist that acts as channel authority. A channel's {@code tvg-id} becomes the emitted
 * XMLTV channel id; an entry without one is skipped rather than given an invented id.
 */
public final class M3uParser {

    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z0-9-]+)=\"([^\"]*)\"");

    private M3uParser() {
    }

    public static List<CanonicalChannel> parse(String content) {
        List<CanonicalChannel> channels = new ArrayList<>();
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (!line.startsWith("#EXTINF")) {
                continue;
            }
            String attributes = line.substring("#EXTINF".length());
            String displayName = null;
            int comma = line.lastIndexOf(',');
            if (comma >= 0) {
                displayName = line.substring(comma + 1).trim();
                attributes = line.substring(0, comma);
            }

            String id = null;
            String name = null;
            String logo = null;
            String number = null;
            Matcher matcher = ATTRIBUTE.matcher(attributes);
            while (matcher.find()) {
                switch (matcher.group(1).toLowerCase()) {
                    case "tvg-id" -> id = matcher.group(2);
                    case "tvg-name" -> name = matcher.group(2);
                    case "tvg-logo" -> logo = matcher.group(2);
                    case "tvg-chno" -> number = matcher.group(2);
                    default -> {
                        // other attributes are not needed by this slice
                    }
                }
            }

            if (id == null || id.isBlank()) {
                continue;
            }
            String label = firstNonBlank(name, displayName, id);
            channels.add(new CanonicalChannel(id.trim(), label, blankToNull(logo), blankToNull(number),
                    CanonicalChannel.Origin.PLAYLIST));
        }
        return channels;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
