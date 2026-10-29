package com.anti.houseepg.application.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.application.guide.port.ChannelAuthorityGateway;
import com.anti.houseepg.application.guide.port.Credentials;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.infrastructure.epg.XmltvReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the two real provider samples in {@code src/test/resources/fixtures} at scale, driven through
 * the real refresh use case (including the registry seeded from the top-priority source).
 *
 * <p>The numbers below are the golden values for those fixtures: if a merge change moves them, that
 * change has to be a deliberate one.
 */
class RealFeedTest {

    private static final SourceId IPTV_EPG = new SourceId("iptv-epg");
    private static final SourceId OPEN_EPG = new SourceId("open-epg");

    private static final Map<String, String> FIXTURES = Map.of(
            "iptv-epg", "/fixtures/real-epg-hu.xml",
            "open-epg", "/fixtures/real-hungary1.xml");

    @Test
    void mergesTheTwoRealSamplesAtScale() {
        RefreshResult result = refreshGuide().refresh();
        Guide guide = result.guide();

        assertThat(result.health()).extracting(SourceHealth::state).containsOnly(SourceHealth.State.OK);
        assertThat(guide.channels()).hasSize(245);
        assertThat(guide.programmes()).hasSize(41841);

        // The same real channel from two providers lands on one id, and it is the primary source's id.
        assertThat(channelIds(guide)).filteredOn(id -> id.contains("AMC")).containsExactly("AMC.hu");

        // A channel only the second source carries survives as a pass-through, not dropped.
        assertThat(guide.channels()).filteredOn(channel -> channel.id().value().equals("360 TuneBox.hu"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PASS_THROUGH));

        // The aggregate invariant, restated from the outside: no programme points at an unknown channel.
        Set<String> declared = channelIds(guide);
        assertThat(guide.programmes()).allSatisfy(programme -> assertThat(declared)
                .contains(programme.channelId().value()));

        // The winner keeps the winning source, so a slot can be explained later.
        assertThat(guide.programmes()).extracting(Programme::winningSource)
                .containsOnly(IPTV_EPG, OPEN_EPG);
    }

    /** The real use case, with the transport swapped for the fixture files. */
    private static RefreshGuide refreshGuide() {
        GuideSettings settings = new GuideSettings(
                List.of(
                        EpgSource.of("iptv-epg", "IPTV-EPG.org", "fixture:iptv-epg", Credentials.none()),
                        EpgSource.of("open-epg", "open-epg.com", "fixture:open-epg", Credentials.none())),
                Optional.empty());
        return new RefreshGuide(settings, RealFeedTest::readFixture, noChannelAuthority());
    }

    private static ChannelAuthorityGateway noChannelAuthority() {
        return location -> {
            throw new IllegalStateException("no playlist in this fixture set");
        };
    }

    private static SourceEpg readFixture(EpgSource source) {
        String resource = FIXTURES.get(source.id().value());
        try (InputStream input = RealFeedTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return new XmltvReader().read(source.id(), input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> channelIds(Guide guide) {
        return guide.channels().stream().map(channel -> channel.id().value()).collect(Collectors.toSet());
    }
}
