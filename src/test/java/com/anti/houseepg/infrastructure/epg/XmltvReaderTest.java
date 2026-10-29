package com.anti.houseepg.infrastructure.epg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.application.guide.port.SourceUnavailableException;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class XmltvReaderTest {

    private static final SourceId SOURCE = new SourceId("test");

    private static final String DOCUMENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv source-info-name="Provider A">
              <channel id="7Tv.xx">
                <display-name>XX - 7.Tv</display-name>
                <icon src="https://example.invalid/icons/7tv.png"/>
              </channel>
              <programme start="20261029164000 +0000" stop="20261029165500 +0000" channel="7Tv.xx">
                <title>Programme 000001</title>
                <desc lang="xx">Synopsis for programme 000001.</desc>
                <category lang="xx">Genre 01</category>
                <episode-num>E00001</episode-num>
              </programme>
              <programme start="20261029200000" stop="20261029210000" channel="7Tv.xx">
                <title>No offset</title>
              </programme>
              <programme start="20261029220000 +0000" channel="7Tv.xx">
                <title>No stop</title>
              </programme>
              <programme start="20261029230000 +0000" stop="20261029220000 +0000" channel="7Tv.xx">
                <title>Backwards</title>
              </programme>
            </tv>
            """;

    @Test
    void readsChannelsAndProgrammes() {
        SourceEpg epg = new XmltvReader().read(SOURCE, stream(DOCUMENT));

        assertThat(epg.source()).isEqualTo(SOURCE);
        assertThat(epg.channels()).singleElement().satisfies(channel -> {
            assertThat(channel.id()).isEqualTo("7Tv.xx");
            assertThat(channel.displayName()).isEqualTo("XX - 7.Tv");
            assertThat(channel.iconUrl()).isEqualTo("https://example.invalid/icons/7tv.png");
        });

        // Programmes without a stop, and slots that end before they start, are skipped.
        assertThat(epg.programmes()).hasSize(2);
        SourceProgramme first = epg.programmes().getFirst();
        assertThat(first.title()).isEqualTo("Programme 000001");
        assertThat(first.description()).isEqualTo("Synopsis for programme 000001.");
        assertThat(first.category()).isEqualTo("Genre 01");
        assertThat(first.episodeNumber()).isEqualTo("E00001");
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
