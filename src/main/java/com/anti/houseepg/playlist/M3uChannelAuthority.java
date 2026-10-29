package com.anti.houseepg.playlist;

import com.anti.houseepg.guide.app.port.ChannelAuthorityGateway;
import com.anti.houseepg.guide.app.port.Credentials;
import com.anti.houseepg.guide.model.Channel;
import com.anti.houseepg.net.HttpFetcher;
import java.util.List;
import org.springframework.stereotype.Component;

/** Adapter: reads the channel authority from an M3U playlist over HTTP. */
@Component
public class M3uChannelAuthority implements ChannelAuthorityGateway {

    private final HttpFetcher fetcher;

    public M3uChannelAuthority(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public List<Channel> channels(String location) {
        return M3uReader.read(fetcher.fetchText(location, Credentials.none()));
    }
}
