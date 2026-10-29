package com.anti.houseepg.xmltv;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.Programme;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class XmltvParserTest {

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
            </tv>
            """;

    @Test
    void readsChannelsAndProgrammes() throws Exception {
        XmltvDocument document = parse(DOCUMENT);

        assertThat(document.channels()).singleElement().satisfies(channel -> {
            assertThat(channel.id()).isEqualTo("9Tv.hu");
            assertThat(channel.displayName()).isEqualTo("HU - 9.Tv");
            assertThat(channel.iconUrl()).isEqualTo("http://example/9tv.png");
        });

        // The programme without a stop is skipped rather than given an invented one.
        assertThat(document.programmes()).hasSize(2);
        Programme first = document.programmes().getFirst();
        assertThat(first.title()).isEqualTo("Hirado");
        assertThat(first.description()).isEqualTo("Hirek.");
        assertThat(first.category()).isEqualTo("hirmusor");
        assertThat(first.episodeNum()).isEqualTo("E1480");
        assertThat(first.start()).isEqualTo(OffsetDateTime.parse("2026-10-29T16:40:00Z"));
        assertThat(first.stop()).isEqualTo(OffsetDateTime.parse("2026-10-29T16:55:00Z"));
    }

    @Test
    void treatsAMissingOffsetAsUtc() throws Exception {
        XmltvDocument document = parse(DOCUMENT);

        assertThat(document.programmes().get(1).start()).isEqualTo(OffsetDateTime.parse("2026-10-29T20:00:00Z"));
    }

    @Test
    void formatsTimesBackToXmltvWithoutLoss() throws Exception {
        Programme programme = parse(DOCUMENT).programmes().getFirst();

        assertThat(XmltvTime.format(programme.start())).isEqualTo("20261029164000 +0000");
        assertThat(XmltvTime.format(programme.stop())).isEqualTo("20261029165500 +0000");
    }

    @Test
    void rejectsProgrammesThatEndBeforeTheyStart() throws Exception {
        String broken = """
                <tv>
                  <programme start="20261029200000 +0000" stop="20261029190000 +0000" channel="x">
                    <title>Backwards</title>
                  </programme>
                </tv>
                """;

        assertThat(parse(broken).programmes()).isEmpty();
    }

    private static XmltvDocument parse(String xml) throws Exception {
        return XmltvParser.parse("test", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
