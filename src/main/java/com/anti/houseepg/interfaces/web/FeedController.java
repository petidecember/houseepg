package com.anti.houseepg.interfaces.web;

import com.anti.houseepg.application.guide.RefreshGuide;
import com.anti.houseepg.application.guide.RefreshResult;
import com.anti.houseepg.application.guide.ServeGuide;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Presentation adapter: the merged feed the DVR consumes, plus the per-source health the UI shows. */
@RestController
public class FeedController {

    private final ServeGuide serveGuide;
    private final RefreshGuide refreshGuide;
    private final XmltvFeedWriter writer;

    public FeedController(ServeGuide serveGuide, RefreshGuide refreshGuide, XmltvFeedWriter writer) {
        this.serveGuide = serveGuide;
        this.refreshGuide = refreshGuide;
        this.writer = writer;
    }

    @GetMapping(value = "/xmltv", produces = "application/xml;charset=UTF-8")
    public String xmltv() {
        return writer.write(serveGuide.current().guide());
    }

    @GetMapping("/api/sources/status")
    public List<SourceHealthResponse> sourceStatus() {
        RefreshResult current = serveGuide.current();
        return current.health().stream()
                .map(health -> SourceHealthResponse.from(health, current.slotLosses().of(health.source())))
                .toList();
    }

    /** Manual "refresh now" for one source (design §8); 404 when no such source is configured. */
    @PostMapping("/api/sources/{id}/refresh")
    public ResponseEntity<SourceHealthResponse> refresh(@PathVariable String id) {
        SourceId source = new SourceId(id);
        if (refreshGuide.sources().stream().noneMatch(candidate -> candidate.id().equals(source))) {
            return ResponseEntity.notFound().build();
        }
        RefreshResult result = refreshGuide.refreshSource(source);
        return result.health().stream()
                .filter(health -> health.source().equals(source))
                .findFirst()
                .map(health -> SourceHealthResponse.from(health, result.slotLosses().of(source)))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
