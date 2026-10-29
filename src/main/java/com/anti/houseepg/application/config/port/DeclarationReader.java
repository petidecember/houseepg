package com.anti.houseepg.application.config.port;

import com.anti.houseepg.application.guide.port.EpgSource;
import java.util.List;

/**
 * Re-reads the source declarations from the configuration file, which is the only place they live
 * (ADR-0005). The application layer asks for this when the user presses "reload config"; it never sees
 * a file path, a YAML node or a Spring property.
 *
 * <p>Only the sources are re-read. The playlist and the retention stay as they were booted, so a reload
 * cannot move an emitted channel id by changing the channel authority (ADR-0002).
 */
@FunctionalInterface
public interface DeclarationReader {

    /** The declared sources, in declared order; an unreadable configuration fails rather than degrades. */
    List<EpgSource> read();
}
