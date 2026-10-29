package com.anti.houseepg.xmltv;

import com.anti.houseepg.domain.Programme;
import com.anti.houseepg.domain.SourceChannel;
import java.util.List;

/** One parsed source document: the channels it declares and the programmes it carries. */
public record XmltvDocument(List<SourceChannel> channels, List<Programme> programmes) {

    public XmltvDocument {
        channels = channels == null ? List.of() : List.copyOf(channels);
        programmes = programmes == null ? List.of() : List.copyOf(programmes);
    }
}
