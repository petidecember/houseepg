package com.anti.houseepg.infrastructure.epg;

import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.application.guide.port.EpgSourceGateway;
import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.RawSnapshot;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.infrastructure.net.HttpFetcher;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter: reads an EPG source over HTTP as XMLTV. Only a payload that parsed is kept as the raw
 * snapshot, so a failed or unparseable fetch never overwrites the last good one (design section 7).
 */
@Component
public class HttpEpgSourceGateway implements EpgSourceGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpEpgSourceGateway.class);

    private final HttpFetcher fetcher;
    private final XmltvReader reader;
    private final GuideStore store;

    public HttpEpgSourceGateway(HttpFetcher fetcher, XmltvReader reader, GuideStore store) {
        this.fetcher = fetcher;
        this.reader = reader;
        this.store = store;
    }

    @Override
    public SourceEpg read(EpgSource source) {
        byte[] payload = fetcher.fetch(source.location(), source.credentials());
        SourceEpg document = reader.read(source.id(), new ByteArrayInputStream(payload));
        keepRawSnapshot(source, payload);
        return document;
    }

    /**
     * The raw payload is a diagnosis aid, not the product: a real feed can exceed the document size a
     * database will accept, and a source must never be reported as failed because a debugging copy could
     * not be written. Only the parsed document is authoritative.
     */
    private void keepRawSnapshot(EpgSource source, byte[] payload) {
        Instant fetchedAt = Instant.now();
        RawSnapshot snapshot = payload.length <= RawSnapshot.MAX_CONTENT_BYTES
                ? new RawSnapshot(source.id(), fetchedAt, new String(payload, StandardCharsets.UTF_8), payload.length)
                : RawSnapshot.oversized(source.id(), fetchedAt, payload.length);
        try {
            store.saveRawSnapshot(snapshot);
            if (snapshot.isOversized()) {
                log.info("Source {} returned {} bytes, above the {} byte stored snapshot limit; keeping only its timestamp",
                        source.id(), payload.length, RawSnapshot.MAX_CONTENT_BYTES);
            }
        } catch (RuntimeException e) {
            log.warn("Could not persist the raw snapshot of source {} ({} bytes); the parsed guide is unaffected: {}",
                    source.id(), payload.length, e.getMessage());
        }
    }
}
