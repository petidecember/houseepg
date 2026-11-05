package com.anti.houseepg.interfaces.web;

import com.anti.houseepg.application.guide.AcceptMatches;
import com.anti.houseepg.application.guide.DecideMatch;
import com.anti.houseepg.application.guide.InspectGuide;
import com.anti.houseepg.application.guide.MergeBreakdown;
import com.anti.houseepg.application.guide.ReviewMatches;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Decision;
import java.util.Optional;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.ChannelMatching;
import com.anti.houseepg.domain.guide.policy.QualityTag;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Presentation adapter for looking at the merge: the registry with the matches behind it, one channel's
 * timeline, and the human decision that fixes a wrong match (design §10).
 */
@RestController
public class InspectionController {

    private final InspectGuide inspectGuide;
    private final DecideMatch decideMatch;
    private final ReviewMatches reviewMatches;
    private final AcceptMatches acceptMatches;
    private final MergeBreakdown mergeBreakdown;

    public InspectionController(InspectGuide inspectGuide, DecideMatch decideMatch,
            ReviewMatches reviewMatches, AcceptMatches acceptMatches, MergeBreakdown mergeBreakdown) {
        this.inspectGuide = inspectGuide;
        this.decideMatch = decideMatch;
        this.reviewMatches = reviewMatches;
        this.acceptMatches = acceptMatches;
        this.mergeBreakdown = mergeBreakdown;
    }

    @GetMapping("/api/channels")
    public ChannelsResponse channels() {
        InspectGuide.Registry registry = inspectGuide.registry();
        return new ChannelsResponse(
                registry.channels().stream().map(ChannelDto::from).toList(),
                registry.matches().stream()
                        .map(match -> MatchDto.from(match, registry.sourceChannels().get(match.source())))
                        .toList());
    }

    /**
     * The review list: one row per source channel that still needs a decision, with the ranked
     * candidates the domain suggests. {@code fuzzy} is a request parameter rather than a setting, so the
     * switch costs no restart and the rule stays in one tested place.
     */
    @GetMapping("/api/review")
    public ResponseEntity<?> review(@RequestParam(name = "fuzzy", defaultValue = "false") boolean fuzzy,
            @RequestParam(name = "filter", defaultValue = "needs-decision") String filter) {
        try {
            return ResponseEntity.ok(ReviewResponse.from(
                    reviewMatches.review(fuzzy, ReviewMatches.Filter.parse(filter))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new Problem(e.getMessage()));
        }
    }

    /**
     * Accepts the decisions the review list is showing, in one write and one re-merge. The set comes from the
     * panel, because only the panel knows which rows are on screen; the server validates it. A dry run
     * reports the same set without writing, so the preview cannot disagree with the apply.
     */
    @PostMapping("/api/matches/accept")
    public ResponseEntity<?> accept(@RequestBody AcceptRequest request) {
        try {
            List<Decision> decisions = request.decisions() == null
                    ? List.of()
                    : request.decisions().stream().map(DecisionRow::toDecision).toList();
            return ResponseEntity.ok(AcceptResponse.from(acceptMatches.accept(decisions, request.dryRun())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new Problem(e.getMessage()));
        }
    }

    /** The merged timeline for one channel, each slot carrying the source that won it. */
    @GetMapping("/api/preview/{channelId}")
    public ResponseEntity<TimelineResponse> preview(@PathVariable String channelId) {
        return inspectGuide.preview(new ChannelId(channelId))
                .map(TimelineResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
    /**
     * The merge preview: one channel, one window, every provider's fate and the feed that results (design
     * §6.6). {@code day} and {@code from} are required — the panel picks them from the window the channel
     * actually has, and a preview of an unstated day would be a different question than the one asked.
     * {@code order} is the draft order being previewed; omitting it previews the applied one.
     */
    @GetMapping("/api/merge/{canonicalChannelId}")
    public ResponseEntity<?> merge(@PathVariable String canonicalChannelId,
            @RequestParam(name = "day") String day,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "hours") double hours,
            @RequestParam(name = "order", required = false) List<String> order) {
        try {
            ZoneId zone = ZoneId.systemDefault();
            LocalDate date = LocalDate.parse(day);
            LocalTime time = LocalTime.parse(from);
            if (!(hours > 0)) {
                throw new IllegalArgumentException("hours has to be greater than zero");
            }
            OffsetDateTime start = date.atTime(time).atZone(zone).toOffsetDateTime();
            Slot window = new Slot(start, start.plusMinutes(Math.round(hours * 60)));
            MergeBreakdown.Breakdown breakdown = mergeBreakdown.breakdown(new ChannelId(canonicalChannelId),
                    window,
                    Optional.ofNullable(order).map(ids -> ids.stream().map(SourceId::new).toList()));
            if (breakdown.channel() == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(MergeResponse.from(breakdown));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new Problem(e.getMessage()));
        }
    }
    /** Confirms or corrects a match; a curated row survives every later automatic pass. */
    @PutMapping("/api/matches")
    public ResponseEntity<?> decide(@RequestBody DecideRequest request) {
        if (request.source() == null || request.source().isBlank()
                || request.sourceChannelId() == null || request.sourceChannelId().isBlank()
                || request.canonicalId() == null || request.canonicalId().isBlank()) {
            return ResponseEntity.badRequest().body(new Problem("source, sourceChannelId and canonicalId are required"));
        }
        try {
            SourceChannelRef source = new SourceChannelRef(new SourceId(request.source()),
                    request.sourceChannelId());
            ChannelId canonicalId = new ChannelId(request.canonicalId());
            // The state is derived unless the caller names one: the same target is a confirmation, a
            // different one is a correction, so the UI does not have to ask a question with no effect.
            ChannelMatch decision = request.decision() == null || request.decision().isBlank()
                    ? decideMatch.decide(source, canonicalId)
                    : decideMatch.decide(source, canonicalId,
                            "corrected".equalsIgnoreCase(request.decision()));
            return ResponseEntity.ok(MatchDto.from(decision,
                    inspectGuide.sourceChannel(decision.source()).orElse(null)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new Problem(e.getMessage()));
        }
    }

    public record ChannelsResponse(List<ChannelDto> channels, List<MatchDto> matches) {
    }

    public record ChannelDto(String id, String name, String number, String origin, String iconUrl,
            String qualityTag) {

        static ChannelDto from(Channel channel) {
            return new ChannelDto(channel.id().value(), channel.label(), channel.number(),
                    channel.origin().name(), channel.iconUrl(), qualityTag(channel));
        }

        private static String qualityTag(Channel channel) {
            return QualityTag.of(channel.label()).map(QualityTag::value).orElse(null);
        }
    }

    /** The panel sends the rows it is showing; the request carries no way to widen that set. */
    public record AcceptRequest(boolean dryRun, List<DecisionRow> decisions) {
    }

    public record DecisionRow(String source, String sourceChannelId, String canonicalId) {

        Decision toDecision() {
            return new Decision(new SourceId(source), sourceChannelId, new ChannelId(canonicalId));
        }
    }

    public record AcceptResponse(int count, boolean dryRun, List<AcceptRowDto> rows) {

        static AcceptResponse from(AcceptMatches.Result result) {
            return new AcceptResponse(result.count(), result.dryRun(),
                    result.rows().stream().map(AcceptRowDto::from).toList());
        }
    }

    public record AcceptRowDto(String source, String sourceChannelId, String canonicalId) {

        static AcceptRowDto from(ChannelMatch match) {
            return new AcceptRowDto(match.source().source().value(), match.source().channelId(),
                    match.canonicalId().value());
        }
    }

    public record ReviewResponse(List<ReviewRowDto> rows, List<ChannelDto> channels) {

        static ReviewResponse from(ReviewMatches.Review review) {
            return new ReviewResponse(
                    review.rows().stream().map(ReviewRowDto::from).toList(),
                    review.channels().stream().map(ChannelDto::from).toList());
        }
    }

    public record ReviewRowDto(String source, String sourceChannelId, String sourceChannelName,
            String sourceIconUrl, String canonicalId, String confidence, String state, boolean curated,
            List<CandidateDto> candidates) {

        static ReviewRowDto from(ReviewMatches.Row row) {
            ChannelMatch match = row.match();
            return new ReviewRowDto(
                    match.source().source().value(),
                    match.source().channelId(),
                    row.sourceChannel() == null ? null : row.sourceChannel().displayName(),
                    row.sourceChannel() == null ? null : row.sourceChannel().iconUrl(),
                    match.canonicalId().value(),
                    match.confidence().name(),
                    match.state().name(),
                    match.isCurated(),
                    row.candidates().stream().map(CandidateDto::from).toList());
        }
    }

    public record CandidateDto(String id, String name, String iconUrl, String qualityTag, String confidence,
            double score) {

        static CandidateDto from(ChannelMatching.Candidate candidate) {
            Channel channel = candidate.channel();
            return new CandidateDto(channel.id().value(), channel.label(), channel.iconUrl(),
                    QualityTag.of(channel.label()).map(QualityTag::value).orElse(null),
                    candidate.confidence().name(), candidate.score());
        }
    }

    public record MatchDto(String source, String sourceChannelId, String canonicalId, String confidence,
            String state, boolean curated, String sourceChannelName, String sourceIconUrl) {

        static MatchDto from(ChannelMatch match, SourceChannel sourceChannel) {
            return new MatchDto(
                    match.source().source().value(),
                    match.source().channelId(),
                    match.canonicalId().value(),
                    match.confidence().name(),
                    match.state().name(),
                    match.isCurated(),
                    sourceChannel == null ? null : sourceChannel.displayName(),
                    sourceChannel == null ? null : sourceChannel.iconUrl());
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
    /**
     * The preview as the panel draws it (design §6.6): the channel, one column per provider in the
     * previewed order, and the merged result. Every time value is an ISO-8601 offset, exactly like the
     * merged timeline, so the browser formats them in the viewer's zone; a column carries its own window
     * because it is what decides which of its spans are stripes.
     */
    public record MergeResponse(String id, String name, List<ColumnDto> columns, ResultDto result) {

        static MergeResponse from(MergeBreakdown.Breakdown breakdown) {
            return new MergeResponse(
                    breakdown.channel() == null ? null : breakdown.channel().id().value(),
                    breakdown.channel() == null ? null : breakdown.channel().label(),
                    breakdown.columns().stream().map(ColumnDto::from).toList(),
                    ResultDto.from(breakdown.result()));
        }
    }

    /**
     * @param channelId the source channel that matched this canonical channel, null when the provider does
     *     not carry it, which is case 3 of §6.6
     * @param carriesElsewhere the canonical channel this provider's data went to instead, so a silent
     *     column can say where it went; null when it serves no other channel
     * @param window the spans this column fills and the spans it leaves empty
     */
    public record ColumnDto(String source, String channelId, String channelName, String carriesElsewhere,
            List<EntryDto> entries, WindowDto window, String caseLabel) {

        static ColumnDto from(MergeBreakdown.Column column) {
            return new ColumnDto(column.source().value(), column.channelId(), column.sourceChannelName(),
                    column.carriesElsewhere(),
                    column.entries().stream().map(EntryDto::from).toList(),
                    WindowDto.from(column.window()),
                    column.caseLabel());
        }
    }

    /**
     * @param fate WON, LOST to a named overlap, or EXCLUDED because this preview order leaves its source
     *     out of the merge
     * @param lostTo the provider whose earlier entry already claimed the slot, when it lost one
     * @param weaker the fields the displaced entry had and this one does not
     */
    public record EntryDto(String start, String stop, String title, String description, String category,
            String episode, String fate, String lostTo, List<WeaknessDto> weaker) {

        static EntryDto from(MergeBreakdown.Entry entry) {
            return new EntryDto(
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(entry.programme().slot().start()),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(entry.programme().slot().stop()),
                    entry.programme().title(),
                    entry.programme().description(),
                    entry.programme().category(),
                    entry.programme().episodeNumber(),
                    entry.fate().name(),
                    entry.lostTo() == null ? null : entry.lostTo().value(),
                    entry.weaker().stream().map(WeaknessDto::from).toList());
        }
    }

    /** One carried programme and the metadata the merge cost it. */
    public record ResultEntryDto(String start, String stop, String title, String description, String category,
            String episode, String source, List<WeaknessDto> weaker) {

        static ResultEntryDto from(MergeBreakdown.ResultEntry entry) {
            return new ResultEntryDto(
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(entry.programme().slot().start()),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(entry.programme().slot().stop()),
                    entry.programme().title(),
                    entry.programme().description(),
                    entry.programme().category(),
                    entry.programme().episodeNumber(),
                    entry.programme().winningSource().value(),
                    entry.weaker().stream().map(WeaknessDto::from).toList());
        }
    }

    /** A field a programme does not carry while an entry it displaced did. */
    public record WeaknessDto(String field, String from) {

        static WeaknessDto from(MergeBreakdown.Weakness weakness) {
            return new WeaknessDto(weakness.field(), weakness.from().value());
        }
    }

    /** The spans a column covers and the spans it leaves empty, both inside the window. */
    public record WindowDto(List<SpanDto> coverage, List<SpanDto> gaps) {

        static WindowDto from(MergeBreakdown.Window window) {
            return new WindowDto(window.coverage().stream().map(SpanDto::from).toList(),
                    window.gaps().stream().map(SpanDto::from).toList());
        }
    }

    public record SpanDto(String start, String stop) {

        static SpanDto from(MergeBreakdown.Gap gap) {
            return new SpanDto(
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(gap.slot().start()),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(gap.slot().stop()));
        }
    }

    /** What the feed serves for this channel once the previewed order is applied. */
    public record ResultDto(List<ResultEntryDto> entries, WindowDto window) {

        static ResultDto from(MergeBreakdown.Result result) {
            return new ResultDto(result.entries().stream().map(ResultEntryDto::from).toList(),
                    WindowDto.from(result.window()));
        }
    }

    public record Problem(String message) {
    }
}
