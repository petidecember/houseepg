package com.anti.houseepg.guide.app;

import com.anti.houseepg.guide.app.port.EpgSource;
import java.util.List;
import java.util.Optional;

/**
 * What a guide refresh needs to know: the sources in priority order (highest first) and, optionally,
 * the location of the playlist that acts as channel authority.
 */
public record GuideSettings(List<EpgSource> sources, Optional<String> playlistLocation) {

    public GuideSettings {
        sources = sources == null ? List.of() : List.copyOf(sources);
        playlistLocation = playlistLocation == null
                ? Optional.empty()
                : playlistLocation.filter(location -> !location.isBlank());
    }
}
