package com.anti.houseepg.xmltv;

import com.anti.houseepg.domain.CanonicalChannel;
import com.anti.houseepg.domain.MergedProgramme;
import java.io.StringWriter;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/** Renders the served feed. Channel ids are emitted verbatim, never generated. */
public final class XmltvWriter {

    private static final String GENERATOR = "HouseEPG";

    private XmltvWriter() {
    }

    public static String write(List<CanonicalChannel> channels, List<MergedProgramme> programmes) {
        StringWriter output = new StringWriter();
        try {
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("tv");
            writer.writeAttribute("generator-info-name", GENERATOR);

            for (CanonicalChannel channel : channels) {
                writer.writeStartElement("channel");
                writer.writeAttribute("id", channel.id());
                writer.writeStartElement("display-name");
                writer.writeCharacters(channel.displayName() == null ? channel.id() : channel.displayName());
                writer.writeEndElement();
                if (channel.iconUrl() != null && !channel.iconUrl().isBlank()) {
                    writer.writeEmptyElement("icon");
                    writer.writeAttribute("src", channel.iconUrl());
                }
                writer.writeEndElement();
            }

            for (MergedProgramme programme : programmes) {
                writer.writeStartElement("programme");
                writer.writeAttribute("start", XmltvTime.format(programme.start()));
                writer.writeAttribute("stop", XmltvTime.format(programme.stop()));
                writer.writeAttribute("channel", programme.canonicalChannelId());
                element(writer, "title", programme.title());
                element(writer, "desc", programme.description());
                element(writer, "category", programme.category());
                element(writer, "episode-num", programme.episodeNum());
                if (programme.iconUrl() != null && !programme.iconUrl().isBlank()) {
                    writer.writeEmptyElement("icon");
                    writer.writeAttribute("src", programme.iconUrl());
                }
                writer.writeEndElement();
            }

            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not render the XMLTV feed", e);
        }
        return output.toString();
    }

    private static void element(XMLStreamWriter writer, String name, String value) throws XMLStreamException {
        if (value == null || value.isBlank()) {
            return;
        }
        writer.writeStartElement(name);
        writer.writeCharacters(value);
        writer.writeEndElement();
    }
}
