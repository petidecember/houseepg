package com.anti.houseepg.infrastructure.config;

import com.anti.houseepg.application.config.port.ConfigDumpWriter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Puts the configuration dump in the log stream, which is the only place credentials may appear (ADR-0005). */
public class LoggingConfigDumpWriter implements ConfigDumpWriter {

    private static final Logger log = LoggerFactory.getLogger("houseepg.configuration.dump");

    @Override
    public void write(List<String> lines) {
        lines.forEach(log::info);
    }
}
