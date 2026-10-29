package com.anti.houseepg.interfaces.web;

import com.anti.houseepg.application.guide.DecideMatch;
import com.anti.houseepg.application.guide.InspectGuide;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Presentation adapter for looking at the merge: the registry with the matches behind it, one channel's
 * timeline, and the human decision that fixes a wrong match (design §10).
 */
@RestController
public class InspectionController {

    private final InspectGuide inspectGuide;
    private final DecideMatch decideMatch;

    public InspectionController(InspectGuide inspectGuide, DecideMatch decideMatch) {
        this.inspectGuide = inspectGuide;
        this.decideMatch = decideMatch;
    }

    @GetMapping("/api/channels")
    public ChannelsResponse channels() {
        InspectGuide.Registry registry = inspectGuide.registry();
        return new ChannelsResponse(
                registry.channels().stream().map(ChannelDto::from).toList(),
                registry.matches().stream().map(MatchDto::from).toList());
    }

    /** The merged timeline for one channel, each slot carrying the source that won it. */
    @GetMapping("/api/preview/{channelId}")
    public ResponseEntity<TimelineResponse> preview(@PathVariable String channelId) {
        return inspectGuide.preview(new ChannelId(channelId))
                .map(TimelineResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Confirms or corrects a match; a curated row survives every later automatic pass. */
    @PutMapping("/api/matches")
    public ResponseEntity<?> decide(@RequestBody DecideRequest request) {
        if (request.source() == null || request.source().isBlank()
                || request.sourceChannelId() == null || request.sourceChannelId().isBlank()
                || request.canonicalId() == null || request.canonicalId().isBlank()) {
            return ResponseEntity.badRequest().body(new Problem("source, sourceChannelId and canonicalId are required"));
        }
        boolean corrected = "corrected".equalsIgnoreCase(request.decision());
        try {
            ChannelMatch decision = decideMatch.decide(
                    new SourceChannelRef(new SourceId(request.source()), request.sourceChannelId()),
                    new ChannelId(request.canonicalId()),
                    corrected);
            return ResponseEntity.ok(MatchDto.from(decision));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new Problem(e.getMessage()));
        }
    }

    public record ChannelsResponse(List<ChannelDto> channels, List<MatchDto> matches) {
    }

    public record ChannelDto(String id, String name, String number, String origin) {

        static ChannelDto from(Channel channel) {
            return new ChannelDto(channel.id().value(), channel.label(), channel.number(), channel.origin().name());
        }
    }

    public record MatchDto(String source, String sourceChannelId, String canonicalId, String confidence,
            String state, boolean curated) {

        static MatchDto from(ChannelMatch match) {
            return new MatchDto(
                    match.source().source().value(),
                    match.source().channelId(),
                    match.canonicalId().value(),
                    match.confidence().name(),
                    match.state().name(),
                    match.isCurated());
        }
    }

    public record DecideRequest(String source, String sourceChannelId, String canonicalId, String decision) {
    }

    public record TimelineResponse(String id, String name, String materializedAt, List<ProgrammeDto> programmes) {

        static TimelineResponse from(InspectGuide.Timeline timeline) {
            return new TimelineResponse(
                    timeline.channel().id().value(),
                    timeline.channel().label(),
                    timeline.materializedAt() == null ? null : timeline.materializedAt().toString(),
                    timeline.programmes().stream().map(ProgrammeDto::from).toList());
        }
    }

    /** Slot times are emitted as ISO-8601 offsets; the browser formats them in the viewer's zone. */
    public record ProgrammeDto(String start, String stop, String title, String description, String category,
            String episode, String source) {

        static ProgrammeDto from(Programme programme) {
            return new ProgrammeDto(
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(programme.slot().start()),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(programme.slot().stop()),
                    programme.title(),
                    programme.description(),
                    programme.category(),
                    programme.episodeNumber(),
                    programme.winningSource().value());
        }
    }

    public record Problem(String message) {
    }
}
