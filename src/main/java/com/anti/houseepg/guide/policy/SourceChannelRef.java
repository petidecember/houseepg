package com.anti.houseepg.guide.policy;

import com.anti.houseepg.guide.model.SourceId;

/** Locates one channel inside one source, before matching has decided which canonical channel it is. */
public record SourceChannelRef(SourceId source, String channelId) {
}
