package com.anti.houseepg.infrastructure.epg;

import com.anti.houseepg.application.guide.port.SourceUnavailableException;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import com.anti.houseepg.domain.guide.policy.Glob;
import com.anti.houseepg.infrastructure.xmltv.XmltvTime;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption adapter: reads XMLTV into the guide model. Streaming rather than DOM because the
 * public feeds are large, and the DTD and external entities are off because the input is remote and
 * untrusted.
 */
@Component
public class XmltvReader {

    private static final Logger log = LoggerFactory.getLogger(XmltvReader.class);

    /** Reads a document with nothing excluded — the configured source says which ids it wants gone. */
    public SourceEpg read(SourceId source, InputStream input) {
        return read(source, input, List.of());
    }

    /**
     * Reads a document, dropping the channels whose id matches one of {@code excludedChannelIds} and
     * every programme that belonged to them (design §6.2, HOU-33). The filter is applied here, at the
     * anti-corruption boundary, so an excluded channel never reaches the model, the match pass or the
     * store — it cannot come back as an unmatched review row either.
     *
     * <p>The drop is all-or-nothing per channel: a programme whose channel id is excluded is dropped
     * even when the matching declaration was itself dropped for the same reason, and a programme whose
     * channel was never declared is left alone for the existing invariant check to catch.
     */
    public SourceEpg read(SourceId source, InputStream input, List<Glob> excludedChannelIds) {
        List<Glob> exclusions = excludedChannelIds == null ? List.of() : excludedChannelIds;
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);

        List<SourceChannel> channels = new ArrayList<>();
        List<SourceProgramme> programmes = new ArrayList<>();
        Set<String> excluded = new LinkedHashSet<>();
        int droppedProgrammes = 0;

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            try {
                while (reader.hasNext()) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    switch (reader.getLocalName()) {
                        case "channel" -> {
                            SourceChannel channel = readChannel(reader);
                            if (channel != null && !isExcluded(channel.id(), exclusions)) {
                                channels.add(channel);
                            } else if (channel != null) {
                                excluded.add(channel.id());
                            }
                        }
                        case "programme" -> {
                            SourceProgramme programme = readProgramme(reader);
                            if (programme == null) {
                                continue;
                            }
                            // An excluded channel takes its programmes with it. This also has to cover a
                            // programme whose declaration was dropped for the same reason, so the id is
                            // asked about directly rather than the entry being looked up in `excluded`.
                            if (isExcluded(programme.sourceChannelId(), exclusions)) {
                                droppedProgrammes++;
                                continue;
                            }
                            programmes.add(programme);
                        }
                        default -> {
                            // <tv> and anything else this adapter does not need.
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new SourceUnavailableException("could not parse the document: " + e.getMessage(), e);
        }

        reportExclusions(source, exclusions, excluded, droppedProgrammes);
        return new SourceEpg(source, channels, programmes);
    }

    private static boolean isExcluded(String channelId, List<Glob> exclusions) {
        return exclusions.stream().anyMatch(glob -> glob.matches(channelId));
    }

    /**
     * The operator asked for these channels to disappear, so this is not a failure — but a list that
     * matched nothing is worth a word, because the usual reason is a typo and the effect is invisible
     * (HOU-33). One line per refresh either way.
     */
    private static void reportExclusions(SourceId source, List<Glob> exclusions, Set<String> excluded,
            int droppedProgrammes) {
        if (exclusions.isEmpty()) {
            return;
        }
        if (excluded.isEmpty()) {
            log.warn("{}: none of the {} configured exclusion pattern(s) matched a channel: {}",
                    source.value(), exclusions.size(), exclusions);
            return;
        }
        log.info("{}: excluded {} channel(s) and dropped {} programme(s) for them, by pattern {}: {}",
                source.value(), excluded.size(), droppedProgrammes, exclusions, excluded);
    }

    private static SourceChannel readChannel(XMLStreamReader reader) throws XMLStreamException {
        String id = attribute(reader, "id");
        String displayName = null;
        String iconUrl = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "display-name" -> {
                        if (displayName == null) {
                            displayName = reader.getElementText();
                        }
                    }
                    case "icon" -> iconUrl = attribute(reader, "src");
                    default -> {
                        // ignore
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "channel".equals(reader.getLocalName())) {
                break;
            }
        }
        if (id == null || id.isBlank()) {
            return null;
        }
        return new SourceChannel(id, displayName == null || displayName.isBlank() ? id : displayName, iconUrl);
    }

    private static SourceProgramme readProgramme(XMLStreamReader reader) throws XMLStreamException {
        String channelId = attribute(reader, "channel");
        OffsetDateTime start = XmltvTime.parse(attribute(reader, "start"));
        OffsetDateTime stop = XmltvTime.parse(attribute(reader, "stop"));

        String title = null;
        String description = null;
        String category = null;
        String episodeNumber = null;
        String iconUrl = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "title" -> {
                        if (title == null) {
                            title = reader.getElementText();
                        }
                    }
                    case "desc" -> {
                        if (description == null) {
                            description = reader.getElementText();
                        }
                    }
                    case "category" -> {
                        if (category == null) {
                            category = reader.getElementText();
                        }
                    }
                    case "episode-num" -> {
                        if (episodeNumber == null) {
                            episodeNumber = reader.getElementText();
                        }
                    }
                    case "icon" -> iconUrl = attribute(reader, "src");
                    default -> {
                        // ignore
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "programme".equals(reader.getLocalName())) {
                break;
            }
        }

        if (channelId == null || channelId.isBlank() || start == null || stop == null) {
            return null;
        }
        Slot slot;
        try {
            slot = new Slot(start, stop);
        } catch (IllegalArgumentException e) {
            // A slot that does not end after it starts is not something we can place on the timeline.
            return null;
        }
        return new SourceProgramme(channelId, slot, title, description, category, episodeNumber, iconUrl);
    }

    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }
}
