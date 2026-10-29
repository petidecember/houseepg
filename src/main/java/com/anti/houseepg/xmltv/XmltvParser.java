package com.anti.houseepg.xmltv;

import com.anti.houseepg.domain.Programme;
import com.anti.houseepg.domain.SourceChannel;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Stream-parses an XMLTV document. Streaming rather than DOM because the public feeds are large; the
 * DTD and external entities are disabled because the input is remote and untrusted.
 */
public final class XmltvParser {

    private XmltvParser() {
    }

    public static XmltvDocument parse(String sourceId, InputStream input) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);

        List<SourceChannel> channels = new ArrayList<>();
        List<Programme> programmes = new ArrayList<>();

        XMLStreamReader reader = factory.createXMLStreamReader(input);
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (reader.getLocalName()) {
                    case "channel" -> {
                        SourceChannel channel = readChannel(sourceId, reader);
                        if (channel != null) {
                            channels.add(channel);
                        }
                    }
                    case "programme" -> {
                        Programme programme = readProgramme(sourceId, reader);
                        if (programme != null) {
                            programmes.add(programme);
                        }
                    }
                    default -> {
                        // <tv> and anything else this slice does not read.
                    }
                }
            }
        } finally {
            reader.close();
        }
        return new XmltvDocument(channels, programmes);
    }

    private static SourceChannel readChannel(String sourceId, XMLStreamReader reader) throws XMLStreamException {
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
        return new SourceChannel(sourceId, id, displayName == null || displayName.isBlank() ? id : displayName,
                iconUrl);
    }

    private static Programme readProgramme(String sourceId, XMLStreamReader reader) throws XMLStreamException {
        String channelId = attribute(reader, "channel");
        OffsetDateTime start = XmltvTime.parse(attribute(reader, "start"));
        OffsetDateTime stop = XmltvTime.parse(attribute(reader, "stop"));

        String title = null;
        String description = null;
        String category = null;
        String episodeNum = null;
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
                        if (episodeNum == null) {
                            episodeNum = reader.getElementText();
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

        if (channelId == null || channelId.isBlank() || start == null || stop == null
                || !stop.isAfter(start)) {
            return null;
        }
        return new Programme(sourceId, channelId, start, stop, title, description, category, episodeNum, iconUrl);
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
