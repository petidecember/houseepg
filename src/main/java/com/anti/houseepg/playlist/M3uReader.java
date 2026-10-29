package com.anti.houseepg.playlist;

import com.anti.houseepg.guide.model.Channel;
import com.anti.houseepg.guide.model.ChannelId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anti-corruption adapter: reads an M3U playlist into canonical channels. A {@code tvg-id} becomes the
 * emitted channel id; an entry without one is skipped rather than given an invented id (ADR-0002).
 */
public final class M3uReader {

    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z0-9-]+)=\"([^\"]*)\"");

    private M3uReader() {
    }

    public static List<Channel> read(String playlist) {
        List<Channel> channels = new ArrayList<>();
        for (String rawLine : playlist.split("\\R")) {
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
                        // other attributes are not needed
                    }
                }
            }

            if (id == null || id.isBlank()) {
                continue;
            }
            channels.add(new Channel(new ChannelId(id), firstNonBlank(name, displayName, id),
                    blankToNull(logo), blankToNull(number), Channel.Origin.PLAYLIST));
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
