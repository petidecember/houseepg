package com.anti.houseepg.application.guide.port;

import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import java.util.List;

/**
 * Remembers what each source channel was attached to, so a refresh does not forget a human decision
 * and a later need to review or undo a match still has its evidence.
 */
public interface ChannelMatchStore {

    /** Everything known, for the next match pass to reuse. */
    List<ChannelMatch> decisions();

    /**
     * Records the outcome of an automatic match pass. Implementations must never replace a
     * {@link com.anti.houseepg.domain.guide.policy.MatchState#isCurated() curated} row: only an
     * explicit {@link #decide(ChannelMatch)} may do that.
     */
    void saveAuto(List<ChannelMatch> matches);

    /** Records an explicit human decision (confirm or correct). */
    void decide(ChannelMatch decision);

    /**
     * Records many explicit decisions as one write, so a bulk accept costs one round trip and one
     * re-merge instead of one per row.
     */
    void decideAll(List<ChannelMatch> decisions);
}
