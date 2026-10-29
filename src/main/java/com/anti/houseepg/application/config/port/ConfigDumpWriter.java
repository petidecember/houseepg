package com.anti.houseepg.application.config.port;

import java.util.List;

/**
 * Where a configuration dump goes: the log stream, never an HTTP response (ADR-0005). The lines it
 * receives carry credentials, so the adapter must not put them anywhere that outlives the log.
 */
@FunctionalInterface
public interface ConfigDumpWriter {

    void write(List<String> lines);
}
