package com.anti.houseepg.web;

import com.anti.houseepg.guide.model.Channel;
import com.anti.houseepg.guide.model.Guide;
import com.anti.houseepg.guide.model.Programme;
import com.anti.houseepg.xmltv.XmltvTime;
import java.io.StringWriter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

/** Presentation adapter: renders the guide as the XMLTV feed the DVR consumes. */
@Component
public class XmltvFeedWriter {

    private static final String GENERATOR = "HouseEPG";

    public String write(Guide guide) {
        StringWriter output = new StringWriter();
        try {
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("tv");
            writer.writeAttribute("generator-info-name", GENERATOR);

            for (Channel channel : guide.channels()) {
                writer.writeStartElement("channel");
                writer.writeAttribute("id", channel.id().value());
                writer.writeStartElement("display-name");
                writer.writeCharacters(channel.label());
                writer.writeEndElement();
                if (channel.iconUrl() != null && !channel.iconUrl().isBlank()) {
                    writer.writeEmptyElement("icon");
                    writer.writeAttribute("src", channel.iconUrl());
                }
                writer.writeEndElement();
            }

            for (Programme programme : guide.programmes()) {
                writer.writeStartElement("programme");
                writer.writeAttribute("start", XmltvTime.format(programme.slot().start()));
                writer.writeAttribute("stop", XmltvTime.format(programme.slot().stop()));
                writer.writeAttribute("channel", programme.channelId().value());
                element(writer, "title", programme.title());
                element(writer, "desc", programme.description());
                element(writer, "category", programme.category());
                element(writer, "episode-num", programme.episodeNumber());
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
