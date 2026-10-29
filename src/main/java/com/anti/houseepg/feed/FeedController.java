package com.anti.houseepg.feed;

import com.anti.houseepg.domain.GuideSnapshot;
import com.anti.houseepg.domain.SourceStatus;
import com.anti.houseepg.source.GuideService;
import com.anti.houseepg.xmltv.XmltvWriter;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The merged feed the DVR consumes, plus the per-source health the UI shows. */
@RestController
public class FeedController {

    private final GuideService guideService;

    public FeedController(GuideService guideService) {
        this.guideService = guideService;
    }

    @GetMapping(value = "/xmltv", produces = "application/xml;charset=UTF-8")
    public String xmltv() {
        GuideSnapshot snapshot = guideService.snapshot();
        return XmltvWriter.write(snapshot.channels(), snapshot.programmes());
    }

    @GetMapping("/api/sources/status")
    public List<SourceStatus> sourceStatus() {
        return guideService.snapshot().statuses();
    }
}
