package com.anti.houseepg.epg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.guide.app.port.SourceUnavailableException;
import com.anti.houseepg.guide.model.SourceEpg;
import com.anti.houseepg.guide.model.SourceId;
import com.anti.houseepg.guide.model.SourceProgramme;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class XmltvReaderTest {

    private static final SourceId SOURCE = new SourceId("test");

    private static final String DOCUMENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv source-info-name="IPTV-EPG.org">
              <channel id="9Tv.hu">
                <display-name>HU - 9.Tv</display-name>
                <icon src="http://example/9tv.png"/>
              </channel>
              <programme start="20261029164000 +0000" stop="20261029165500 +0000" channel="9Tv.hu">
                <title>Hirado</title>
                <desc lang="hu">Hirek.</desc>
                <category lang="hu">hirmusor</category>
                <episode-num>E1480</episode-num>
              </programme>
              <programme start="20261029200000" stop="20261029210000" channel="9Tv.hu">
                <title>No offset</title>
              </programme>
              <programme start="20261029220000 +0000" channel="9Tv.hu">
                <title>No stop</title>
              </programme>
              <programme start="20261029230000 +0000" stop="20261029220000 +0000" channel="9Tv.hu">
                <title>Backwards</title>
              </programme>
            </tv>
            """;

    @Test
    void readsChannelsAndProgrammes() {
        SourceEpg epg = new XmltvReader().read(SOURCE, stream(DOCUMENT));

        assertThat(epg.source()).isEqualTo(SOURCE);
        assertThat(epg.channels()).singleElement().satisfies(channel -> {
            assertThat(channel.id()).isEqualTo("9Tv.hu");
            assertThat(channel.displayName()).isEqualTo("HU - 9.Tv");
            assertThat(channel.iconUrl()).isEqualTo("http://example/9tv.png");
        });

        // Programmes without a stop, and slots that end before they start, are skipped.
        assertThat(epg.programmes()).hasSize(2);
        SourceProgramme first = epg.programmes().getFirst();
        assertThat(first.title()).isEqualTo("Hirado");
        assertThat(first.description()).isEqualTo("Hirek.");
        assertThat(first.category()).isEqualTo("hirmusor");
        assertThat(first.episodeNumber()).isEqualTo("E1480");
        assertThat(first.slot().start()).isEqualTo(OffsetDateTime.parse("2026-10-29T16:40:00Z"));
        assertThat(first.slot().stop()).isEqualTo(OffsetDateTime.parse("2026-10-29T16:55:00Z"));
    }

    @Test
    void treatsAMissingOffsetAsUtc() {
        SourceEpg epg = new XmltvReader().read(SOURCE, stream(DOCUMENT));

        assertThat(epg.programmes().get(1).slot().start()).isEqualTo(OffsetDateTime.parse("2026-10-29T20:00:00Z"));
    }

    @Test
    void reportsAnUnparseableDocumentAsASourceThatIsUnavailable() {
        assertThatThrownBy(() -> new XmltvReader().read(SOURCE, stream("<tv><channel")))
                .isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("could not parse");
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
