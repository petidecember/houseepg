package com.anti.houseepg.infrastructure.persistence;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.MatchState;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Adapter: channel matches in MongoDB, unique per (source, source channel id); durable since HOU-5. */
@Component
public class MongoChannelMatchStore implements ChannelMatchStore {

    static final String COLLECTION = "matches";

    private static final Logger log = LoggerFactory.getLogger(MongoChannelMatchStore.class);

    private final MongoDatabase database;
    private final AtomicBoolean indexesEnsured = new AtomicBoolean();

    public MongoChannelMatchStore(MongoDatabase database) {
        this.database = database;
    }

    private MongoCollection<Document> matches() {
        if (indexesEnsured.compareAndSet(false, true)) {
            try {
                database.getCollection(COLLECTION)
                        .createIndex(Indexes.compoundIndex(Indexes.ascending("sourceId"),
                                Indexes.ascending("sourceChannelId")), MongoGuideStore.unique());
            } catch (RuntimeException e) {
                indexesEnsured.set(false);
                log.warn("Could not ensure the match index yet: {}", e.toString());
            }
        }
        return database.getCollection(COLLECTION);
    }

    @Override
    public List<ChannelMatch> decisions() {
        return matches().find().into(new ArrayList<>()).stream()
                .map(document -> new ChannelMatch(
                        new SourceChannelRef(new SourceId(document.getString("sourceId")),
                                document.getString("sourceChannelId")),
                        new ChannelId(document.getString("canonicalChannelId")),
                        MatchConfidence.valueOf(document.getString("confidence")),
                        MatchState.valueOf(document.getString("state"))))
                .toList();
    }

    @Override
    public void saveAuto(List<ChannelMatch> matches) {
        MongoCollection<Document> collection = matches();
        for (ChannelMatch match : matches) {
            // A curated row is never downgraded: only an explicit decision may replace it.
            Document existing = collection.find(key(match)).first();
            if (existing != null && MatchState.valueOf(existing.getString("state")).isCurated()) {
                continue;
            }
            write(match);
        }
    }

    @Override
    public void decide(ChannelMatch decision) {
        if (!decision.isCurated()) {
            throw new IllegalArgumentException("a decision must be confirmed or corrected, not " + decision.state());
        }
        write(decision);
    }

    private void write(ChannelMatch match) {
        matches().replaceOne(key(match), document(match), new ReplaceOptions().upsert(true));
    }

    private static Bson key(ChannelMatch match) {
        return and(eq("sourceId", match.source().source().value()),
                eq("sourceChannelId", match.source().channelId()));
    }

    private static Document document(ChannelMatch match) {
        return new Document("sourceId", match.source().source().value())
                .append("sourceChannelId", match.source().channelId())
                .append("canonicalChannelId", match.canonicalId().value())
                .append("confidence", match.confidence().name())
                .append("state", match.state().name());
    }
}
