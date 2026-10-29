package com.anti.houseepg.infrastructure.epg;

import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.application.guide.port.EpgSourceGateway;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.infrastructure.net.HttpFetcher;
import java.io.ByteArrayInputStream;
import org.springframework.stereotype.Component;

/** Adapter: reads an EPG source over HTTP as XMLTV. */
@Component
public class HttpEpgSourceGateway implements EpgSourceGateway {

    private final HttpFetcher fetcher;
    private final XmltvReader reader;

    public HttpEpgSourceGateway(HttpFetcher fetcher, XmltvReader reader) {
        this.fetcher = fetcher;
        this.reader = reader;
    }

    @Override
    public SourceEpg read(EpgSource source) {
        byte[] payload = fetcher.fetch(source.location(), source.credentials());
        return reader.read(source.id(), new ByteArrayInputStream(payload));
    }
}
