package com.anti.houseepg.guide.app.port;

import com.anti.houseepg.guide.model.SourceEpg;

/**
 * Reads one upstream source into the core's own model. Implemented by an adapter, so the wire format
 * never reaches the domain.
 */
public interface EpgSourceGateway {

    SourceEpg read(EpgSource source) throws SourceUnavailableException;
}
