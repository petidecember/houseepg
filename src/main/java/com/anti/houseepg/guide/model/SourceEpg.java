package com.anti.houseepg.guide.model;

import java.util.List;

/**
 * One source's guide data as the core understands it. This is the anti-corruption boundary: whichever
 * wire format arrives, the adapter translates it into this shape.
 */
public record SourceEpg(SourceId source, List<SourceChannel> channels, List<SourceProgramme> programmes) {

    public SourceEpg {
        channels = channels == null ? List.of() : List.copyOf(channels);
        programmes = programmes == null ? List.of() : List.copyOf(programmes);
    }

    public boolean isEmpty() {
        return channels.isEmpty() && programmes.isEmpty();
    }
}
