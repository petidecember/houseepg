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
import org.springframework.stereotype.Component;

/**
 * Adapter: reads an EPG source over HTTP as XMLTV. Only a payload that parsed is kept as the raw
 * snapshot, so a failed or unparseable fetch never overwrites the last good one (design §7).
 */
@Component
public class HttpEpgSourceGateway implements EpgSourceGateway {

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
        store.saveRawSnapshot(new RawSnapshot(source.id(), Instant.now(),
                new String(payload, StandardCharsets.UTF_8)));
        return document;
    }
}
