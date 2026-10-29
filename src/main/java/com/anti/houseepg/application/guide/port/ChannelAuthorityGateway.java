package com.anti.houseepg.application.guide.port;

import com.anti.houseepg.domain.guide.model.Channel;
import java.util.List;

/**
 * Reads the channel authority: the playlist whose ids the feed emits. Implemented by an adapter so the
 * playlist format never reaches the domain.
 */
public interface ChannelAuthorityGateway {

    List<Channel> channels(String location) throws SourceUnavailableException;
}
