package com.anti.houseepg.application.config;

import com.anti.houseepg.application.config.port.ConfigDumpWriter;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.port.Credentials;
import com.anti.houseepg.application.guide.port.EpgSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Use case: write the in-memory configuration to the log stream so a frontend problem can be diagnosed
 * (ADR-0005). It is deliberately ungated and it includes credentials — the log is the one place they
 * are allowed outside the YAML file.
 */
public class DumpConfiguration {

    private final GuideSettings settings;
    private final ConfigDumpWriter writer;

    public DumpConfiguration(GuideSettings settings, ConfigDumpWriter writer) {
        this.settings = settings;
        this.writer = writer;
    }

    public void dump() {
        List<EpgSource> sources = settings.sources();
        List<String> lines = new ArrayList<>();
        lines.add("configuration dump: " + sources.size() + " source(s), highest priority first");
        lines.add("playlist-url: "
                + settings.playlistLocation().orElse("(none — the top-priority source seeds the registry)"));
        lines.add("retention: " + settings.retention());
        for (int index = 0; index < sources.size(); index++) {
            EpgSource source = sources.get(index);
            lines.add("[" + (index + 1) + "] " + source.id().value()
                    + " | name=" + source.name()
                    + " | url=" + source.location()
                    + " | refresh-interval=" + source.refreshInterval()
                    + " | credentials=" + credentials(source.credentials()));
        }
        writer.write(lines);
    }

    private static String credentials(Credentials credentials) {
        if (credentials == null) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        if (credentials.username() != null) {
            parts.add("username=" + credentials.username());
        }
        if (credentials.password() != null) {
            parts.add("password=" + credentials.password());
        }
        if (credentials.headerName() != null) {
            parts.add("header=" + credentials.headerName() + ": " + credentials.headerValue());
        }
        return parts.isEmpty() ? "none" : String.join(" ", parts);
    }
}
