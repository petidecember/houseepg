package com.anti.houseepg.epg;

import com.anti.houseepg.guide.app.port.EpgSource;
import com.anti.houseepg.guide.app.port.EpgSourceGateway;
import com.anti.houseepg.guide.model.SourceEpg;
import com.anti.houseepg.net.HttpFetcher;
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
