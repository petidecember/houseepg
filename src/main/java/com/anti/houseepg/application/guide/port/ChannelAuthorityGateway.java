package com.anti.houseepg.application.guide.port;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.policy.Glob;
import java.util.List;

/**
 * Reads the channel authority: the playlist whose ids the feed emits. Implemented by an adapter so the
 * playlist format never reaches the domain.
 */
public interface ChannelAuthorityGateway {

    List<Channel> channels(String location) throws SourceUnavailableException;

    /**
     * The channels declared in the playlist <em>within {@code groups}</em> (HOU-45). An empty list keeps
     * every group, so a caller that has nothing configured reads what it always read.
     *
     * <p>Deliberately a default method: filtering by group is a capability of a playlist adapter, not a
     * property of the port. An adapter that cannot filter must say so by not overriding this, and the
     * shipped adapter does override it — a stub that only knows one location therefore keeps compiling,
     * and a caller can never read a filtered list it did not actually get.
     */
    default List<Channel> channels(String location, List<Glob> groups) throws SourceUnavailableException {
        return channels(location);
    }
}
