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
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchState;
import com.anti.houseepg.infrastructure.epg.XmltvReader;
import com.anti.houseepg.support.InMemoryChannelMatchStore;
import com.anti.houseepg.support.InMemoryGuideStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the two full-size provider fixtures in {@code src/test/resources/fixtures} at scale, driven
 * through the real refresh use case (including the registry seeded from the top-priority source).
 *
 * <p>The fixtures are white-labelled: channel ids, names, programme text and icons were replaced with
 * neutral values, and programme volume is capped at the first six programmes of each channel so the
 * pair stays under a megabyte. Every channel is kept, including the pairs whose comparison keys
 * collide within one provider and the channels whose id and display name fold to different keys. The
 * numbers below are the golden values for those fixtures: if a merge change moves them, that change
 * has to be a deliberate one.
 */
class ProviderSamplesTest {

    private static final SourceId PROVIDER_A = new SourceId("provider-a");
    private static final SourceId PROVIDER_B = new SourceId("provider-b");

    private final InMemoryChannelMatchStore matchStore = new InMemoryChannelMatchStore();
    private final InMemoryGuideStore guideStore = new InMemoryGuideStore();

    private static final Map<String, String> FIXTURES = Map.of(
            "provider-a", "/fixtures/provider-a.xml",
            "provider-b", "/fixtures/provider-b.xml");

    /**
     * The fixtures span 2026-10-25T16:08Z to 2026-10-31T15:30Z, and a refresh prunes every programme that
     * ended before {@code now - retention} (7 days). Driving the refresh with a fixed instant inside that
     * window keeps the golden numbers below meaningful on any date; calling {@code refresh()} would use
     * the wall clock and quietly prune the fixtures as time passes.
     */
    private static final Instant FIXTURE_NOW = Instant.parse("2026-10-30T00:00:00Z");

    @Test
    void mergesTheTwoProviderSamplesAtScale() {
        RefreshResult result = refreshGuide().refresh();
        Guide guide = result.guide();

        assertThat(result.health()).extracting(SourceHealth::state).containsOnly(SourceHealth.State.OK);
        assertThat(guide.channels()).hasSize(245);
        assertThat(guide.programmes()).hasSize(1818);

        // The same real channel from two providers lands on one id, and it is the primary source's id.
        assertThat(channelIds(guide)).filteredOn(id -> id.startsWith("channel003")).containsExactly("channel003.xx");

        // A channel only the second source carries survives as a pass-through, not dropped.
        assertThat(guide.channels())
                .filteredOn(channel -> channel.id().value().equals("Channel 152 (HD).xx"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PASS_THROUGH));

        // The aggregate invariant, restated from the outside: no programme points at an unknown channel.
        Set<String> declared = channelIds(guide);
        assertThat(guide.programmes()).allSatisfy(programme -> assertThat(declared)
                .contains(programme.channelId().value()));

        // The winner keeps the winning source, so a slot can be explained later.
        assertThat(guide.programmes()).extracting(Programme::winningSource)
                .containsOnly(PROVIDER_A, PROVIDER_B);

        // Every declared source channel is recorded once, and the unmatched ones are exactly the
        // channels that were kept as pass-throughs.
        assertThat(result.matches()).hasSize(330);
        assertThat(result.matches()).extracting(ChannelMatch::source).doesNotHaveDuplicates();
        Set<String> passThroughIds = guide.channels().stream()
                .filter(channel -> channel.origin() == Channel.Origin.PASS_THROUGH)
                .map(channel -> channel.id().value())
                .collect(Collectors.toSet());
        assertThat(result.matches()).filteredOn(match -> match.state() == MatchState.UNMATCHED)
                .extracting(match -> match.canonicalId().value())
                .containsExactlyInAnyOrderElementsOf(passThroughIds);
    }

    /** The real use case, with the transport swapped for the fixture files. */
    private RefreshGuide refreshGuide() {
        GuideSettings settings = new GuideSettings(
                List.of(
                        EpgSource.of("provider-a", "Provider A", "fixture:provider-a", Credentials.none()),
                        EpgSource.of("provider-b", "Provider B", "fixture:provider-b", Credentials.none())),
                Optional.empty());
        return new RefreshGuide(settings, ProviderSamplesTest::readFixture, noChannelAuthority(), matchStore,
                guideStore, Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC));
    }

    private static ChannelAuthorityGateway noChannelAuthority() {
        return location -> {
            throw new IllegalStateException("no playlist in this fixture set");
        };
    }

    private static SourceEpg readFixture(EpgSource source) {
        String resource = FIXTURES.get(source.id().value());
        try (InputStream input = ProviderSamplesTest.class.getResourceAsStream(resource)) {
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
