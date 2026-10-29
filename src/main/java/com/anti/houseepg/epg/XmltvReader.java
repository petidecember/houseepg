package com.anti.houseepg.epg;

import com.anti.houseepg.guide.app.port.SourceUnavailableException;
import com.anti.houseepg.guide.model.Slot;
import com.anti.houseepg.guide.model.SourceChannel;
import com.anti.houseepg.guide.model.SourceEpg;
import com.anti.houseepg.guide.model.SourceId;
import com.anti.houseepg.guide.model.SourceProgramme;
import com.anti.houseepg.xmltv.XmltvTime;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption adapter: reads XMLTV into the guide model. Streaming rather than DOM because the
 * public feeds are large, and the DTD and external entities are off because the input is remote and
 * untrusted.
 */
@Component
public class XmltvReader {

    public SourceEpg read(SourceId source, InputStream input) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);

        List<SourceChannel> channels = new ArrayList<>();
        List<SourceProgramme> programmes = new ArrayList<>();

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
                            if (channel != null) {
                                channels.add(channel);
                            }
                        }
                        case "programme" -> {
                            SourceProgramme programme = readProgramme(reader);
                            if (programme != null) {
                                programmes.add(programme);
                            }
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

        return new SourceEpg(source, channels, programmes);
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
